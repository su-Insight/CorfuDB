package org.corfudb.infrastructure.logreplication.replication.send;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.corfudb.infrastructure.logreplication.infrastructure.LogReplicationContext;
import org.corfudb.infrastructure.logreplication.replication.receive.LogReplicationMetadataManager;
import org.corfudb.infrastructure.logreplication.replication.send.logreader.LogEntryReader;
import org.corfudb.infrastructure.logreplication.replication.send.logreader.LogEntryReader.StreamIteratorMetadata;
import org.corfudb.runtime.LogReplication.SyncType;
import org.corfudb.runtime.LogReplication.LogReplicationSession;
import org.corfudb.runtime.LogReplication.SyncStatus;
import org.corfudb.protocols.wireprotocol.StreamAddressRange;
import org.corfudb.runtime.CorfuRuntime;
import org.corfudb.runtime.exceptions.TransactionAbortedException;
import org.corfudb.runtime.exceptions.unrecoverable.UnrecoverableCorfuInterruptedError;
import org.corfudb.runtime.view.Address;
import org.corfudb.runtime.view.ObjectsView;
import org.corfudb.runtime.view.stream.StreamAddressSpace;
import org.corfudb.util.retry.IRetry;
import org.corfudb.util.retry.IntervalRetry;
import org.corfudb.util.retry.RetryNeededException;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@ToString
@Slf4j
public class LogReplicationAckReader {
    private final LogReplicationMetadataManager metadataManager;
    private final CorfuRuntime runtime;
    private final LogReplicationSession session;
    private final LogReplicationContext replicationContext;

    // Log tail when the current snapshot sync started.  We do not need to synchronize access to it because it will not
    // be read(calculateRemainingEntriesToSend) and written(setBaseSnapshot) concurrently.
    private long baseSnapshotTimestamp;

    // Periodic Thread which reads the last acknowledged timestamp and writes it to the metadata table
    // TODO V2: tune the thread count
    private static ScheduledExecutorService lastAckedTsPoller = Executors.newScheduledThreadPool(2,
            new ThreadFactoryBuilder().setNameFormat("ack-timestamp-reader-%d").build());

    // Interval at which the thread reads the last acknowledged timestamp
    public static final int ACKED_TS_READ_INTERVAL_SECONDS = 15;

    private static final int NO_REPLICATION_REMAINING_ENTRIES = 0;

    // Last ack'd timestamp from Receiver
    private long lastAckedTimestamp = Address.NON_ADDRESS;

    // Sync Type for which last Ack was received, it is initialized when setSyncType is called
    private SyncType lastSyncType = null;

    private LogEntryReader logEntryReader;

    private LogEntrySender logEntrySender;

    private final Lock lock = new ReentrantLock();

    public LogReplicationAckReader(LogReplicationMetadataManager metadataManager, CorfuRuntime runtime,
                                   LogReplicationSession session, LogReplicationContext replicationContext) {
        this.metadataManager = metadataManager;
        this.runtime = runtime;
        this.session = session;
        this.replicationContext = replicationContext;
    }

    public void setAckedTsAndSyncType(long ackedTs, SyncType syncType) {
        lock.lock();
        try {
            lastAckedTimestamp = ackedTs;
            lastSyncType = syncType;
        } finally {
            lock.unlock();
        }
    }

    public void setSyncType(SyncType syncType) {
        lock.lock();
        try {
            lastSyncType = syncType;
        } finally {
            lock.unlock();
        }
    }

    public void setLogEntryReader(LogEntryReader logEntryReader) {
        this.logEntryReader = logEntryReader;
    }

    public void setLogEntrySender(LogEntrySender logEntrySender) {
        this.logEntrySender = logEntrySender;
    }

    /**
     * Given a timestamp acked by the receiver, calculate how many entries remain to be sent for all replicated streams.
     *
     * @param ackedTimestamp Timestamp ack'd by the receiver
     *
     * For Log Entry Sync, this function returns the total number of entries remaining to be sent across all replicated
     * streams based on LOG_REPLICATION_STREAM.
     *
     * For Snapshot Sync, a snapshot of each stream at a given point in time is sent. The stream could have been
     * checkpointed and trimmed so we cannot find the remaining number of entries accurately. In this case, we simply
     * subtract the acked timestamp from the global log tail when the snapshot sync started.
     * Note that this method is not accurate because the global tail can reflect the interleaving of replicated and
     * non-replicated streams, and hence, does not accurately represent the remaining entries to send for replicated streams.
     *
     * If there is no data on the source, it returns 0, which means no replication remaining.
     * If the ack'd timestamp is uninitialized(no ack received), it returns the log tail, which means no replication has
     * been done.
     */
    private long calculateRemainingEntriesToSend(long ackedTimestamp) {
        // Get all streams tails, which will be used in the computation of remaining entries
        Map<UUID, Long> tailMap = runtime.getAddressSpaceView().getAllTails().getStreamTails();

        long txStreamTail = getTxStreamTail(tailMap);
        long maxReplicatedStreamTail = getMaxReplicatedStreamsTail(tailMap);
        StreamIteratorMetadata currentTxStreamProcessedTs = logEntryReader.getCurrentProcessedEntryMetadata();

        if (log.isTraceEnabled()) {
            log.trace("calculateRemainingEntriesToSend:: maxTailReplicateStreams={}, txStreamTail={}, lastTxStreamProcessedTs={}, " +
                            "lastTxStreamProcessedStreamsPresent={}, sync={}",
                    maxReplicatedStreamTail, txStreamTail, currentTxStreamProcessedTs.getTimestamp(),
                    currentTxStreamProcessedTs.isStreamsToReplicatePresent(), lastSyncType);
        }

        // No data to send on the source, so no replication remaining
        if (maxReplicatedStreamTail == Address.NON_ADDRESS) {
            log.debug("No data to replicate, replication complete.");
            return NO_REPLICATION_REMAINING_ENTRIES;
        }

        if (lastSyncType.equals(SyncType.SNAPSHOT)) {

            // If during snapshot sync nothing has been ack'ed, all replication is remaining
            if (ackedTimestamp == Address.NON_ADDRESS) {
                ackedTimestamp = 0;
            }

            // In Snapshot Sync
            // Simply subtract the ack'ed timestamp from the global log tail from the time the snapshot sync started.
            // Note that this is not accurate because the global log tail does not accurately represent the remaining entries
            // for replicated streams.
            // When snapshot sync is ongoing, there may be delta updates also. Add those new entries by querying the address maps
            return ((baseSnapshotTimestamp - ackedTimestamp) +
                    getTxStreamTotalEntries(baseSnapshotTimestamp, txStreamTail));
        }

        // In Log Entry Sync
        return calculateRemainingEntriesIncrementalUpdates(ackedTimestamp, txStreamTail, currentTxStreamProcessedTs);
    }

    private long getTxStreamTail(Map<UUID, Long> tailMap) {
        if (tailMap.containsKey(ObjectsView.getLogReplicatorStreamId())) {
            return tailMap.get(ObjectsView.getLogReplicatorStreamId());
        }

        log.warn("Tx Stream tail not present in sequencer, id={}", ObjectsView.getLogReplicatorStreamId());
        return Address.NON_ADDRESS;
    }

    /**
     * For the given replication runtime, query max stream tail for all streams to be replicated.
     *
     * @return max tail of all streams to be replicated for the given runtime
     */
    private long getMaxReplicatedStreamsTail(Map<UUID, Long> tailMap) {
        long maxTail = Address.NON_ADDRESS;
        replicationContext.refresh();
        Set<String> streamsToReplicate = replicationContext.getConfig(session).getStreamsToReplicate();
        for (String streamName : streamsToReplicate) {
            UUID streamUuid = CorfuRuntime.getStreamID(streamName);
            if (tailMap.containsKey(streamUuid)) {
                long streamTail = tailMap.get(streamUuid);
                maxTail = Math.max(maxTail, streamTail);
            }
        }
        return maxTail;
    }

    /**
     * The calculation of remaining entries during log entry sync takes into account:
     * - lastAckedTimestamp: last timestamp of entry for which we've received an ACK
     * - txStreamTail: transaction stream tail
     * - currentTxStreamProcessedTs: metadata of current entry being processed by the StreamsLogEntryReader,
     *           it contains the timestamp and a boolean indicating if that entry has data to replicate or not,
     *           this will give an indication whether if an ACK is expected or not.
     *
     * Consider the following representation of the data log, where entries 20, 50, 60, 70 belong to the
     * tx stream.
     *

     +---+---+---+---+---+---+---+---+---+---+---+---+
     |   |   |   |   |   |   |   |   |   |   |   |   |
     +---+---+---+---+---+---+---+---+---+---+---+---+
     20          50          60       70         100
     tx          tx          tx       tx        (log tail / not part of tx stream)


     * Case 1: Log Entry Sync lagging behind (in processing)
     *          - lastAckedTimestamp = 50
     *          - txStreamTail = 70
     *          - currentTxStreamProcessedTs = 60, true (does contain streams to replicate)
     *
     * (despite the current processed not requiring ACk, there might be entries between lastAcked and currentProcessed
     * which still have not been acknowledged so we check sender's pendingQueue's size
     *          remainingEntries = entriesBetween(60, 70] + pendingQueueSize = 1 + 1 = 2
     *
     * Special case for Case 1, when  currentTxStreamProcessedTs = -1 which basically means no log entry has been processed,
     * in this case we should return 0.

     * Case 2.0: Log Entry Sync Up to Date
     *          - lastAckedTimestamp = 70
     *          - txStreamTail = 70
     *          - currentTxStreamProcessedTs = 70, true (contains replicated streams)
     *
     *          remainingEntries = 0

     * Case 2.1: Log Entry Sync Up to Date
     *          - lastAckedTimestamp = 60
     *          - txStreamTail = 70
     *          - currentTxStreamProcessedTs = 70, false (does not contain replicated streams,
     *          so there is no expectation that lastAckedTimestamp reaches 70)
     *
     *          remainingEntries = 0


     * Case 2.2: Log Entry Sync Lagging Behind (in ack)
     *          - lastAckedTimestamp = 60
     *          - txStreamTail = 70
     *          - currentTxStreamProcessedTs = 70, true (does contain replicated streams,
     *           wait until lastAckedTimestamp reflects this)
     *
     *          remainingEntries = entriesBetween(60, 70] = 1
     *
     */
    private long calculateRemainingEntriesIncrementalUpdates(long lastAckedTs, long txStreamTail,
                                                             StreamIteratorMetadata currentTxStreamProcessedTs) {
        long noRemainingEntriesToSend = 0;

        // If we have processed up to or beyond the latest known tx stream tail
        // we can assume we are up to date.
        // Note: in the case of a switchover the tail won't be moving so we can assume
        // the tx stream last processed timestamp will never be above the stream's tail,
        // but in the case of ongoing replication or holes, it might be above.
        // We can't do much other than report that we're up to date (as it might continue moving)
        if (txStreamTail <= currentTxStreamProcessedTs.getTimestamp()) {
            // (Case 2 from description)
            // If the current processed tx stream entry has data to replicate,
            // we need to ensure we have received an ACK it, otherwise,
            // we might be signalling completion when there is still an entry for which
            // we haven't received confirmation of the recipient.
            if (currentTxStreamProcessedTs.isStreamsToReplicatePresent()) {
                if (lastAckedTs == currentTxStreamProcessedTs.getTimestamp()) {
                    if (log.isTraceEnabled()) {
                        log.trace("Log Entry Sync up to date, lastAckedTs={}, txStreamTail={}, currentTxProcessedTs={}, containsEntries={}", lastAckedTs,
                                txStreamTail, currentTxStreamProcessedTs.getTimestamp(), currentTxStreamProcessedTs.isStreamsToReplicatePresent());
                    }
                    // (Case 2.0)
                    return noRemainingEntriesToSend;
                }

                // (Case 2.2)
                // Last ack'ed timestamp should match the last processed tx stream timestamp.
                // Calculate how many entries are missing, based on the tx stream's address map
                if (log.isTraceEnabled()) {
                    log.trace("Log Entry Sync pending ACKs, lastAckedTs={}, txStreamTail={}, currentTxProcessedTs={}, containsEntries={}", lastAckedTs,
                            txStreamTail, currentTxStreamProcessedTs.getTimestamp(), currentTxStreamProcessedTs.isStreamsToReplicatePresent());
                }
                return getTxStreamTotalEntries(lastAckedTs, currentTxStreamProcessedTs.getTimestamp());
            }

            // (Case 2.1)
            // Since last tx stream processed timestamp is not intended to be replicated
            // and we're at or beyond the last known tail, no entries remaining to be sent
            // at this point.
            if (log.isTraceEnabled()) {
                log.trace("Log Entry Sync up to date, no pending ACKs, lastAckedTs={}, txStreamTail={}, currentTxProcessedTs={}, containsEntries={}", lastAckedTs,
                        txStreamTail, currentTxStreamProcessedTs.getTimestamp(), currentTxStreamProcessedTs.isStreamsToReplicatePresent());
            }
            return noRemainingEntriesToSend;
        }

        // (Case 1)
        long currentTxStreamProcessed = currentTxStreamProcessedTs.getTimestamp();
        long remainingEntries = 0;

        // If currentTxStreamProcessedTs is the default (NON_ADDRESS), it means no log entry (delta) has been found
        // hence there is no remaining entries to replicate
        if (currentTxStreamProcessed != Address.NON_ADDRESS) {

            remainingEntries = getTxStreamTotalEntries(currentTxStreamProcessed, txStreamTail);

            if (logEntrySender != null) {
                remainingEntries += logEntrySender.getPendingACKQueueSize();
            }
        }

        if (log.isTraceEnabled()) {
            log.trace("Log Entry Sync pending entries for processing, lastAckedTs={}, txStreamTail={}, " +
                            "currentTxProcessedTs={}, containsEntries={}, remaining={}", lastAckedTs,
                    txStreamTail, currentTxStreamProcessedTs.getTimestamp(),
                    currentTxStreamProcessedTs.isStreamsToReplicatePresent(), remainingEntries);
        }

        return remainingEntries;
    }

    private long getTxStreamTotalEntries(long lowerBoundary, long upperBoundary) {
        long totalEntries = 0;

        if (upperBoundary > lowerBoundary) {
            StreamAddressRange range = new StreamAddressRange(ObjectsView.getLogReplicatorStreamId(), upperBoundary, lowerBoundary);
            StreamAddressSpace txStreamAddressSpace = runtime.getSequencerView().getStreamAddressSpace(range);
            // Count how many entries are present in the Tx Stream (this can include holes,
            // valid entries and invalid entries), but we count them all (equal weight).
            // An invalid entry, is a transactional entry with no streams to replicate (which will be ignored)
            totalEntries = txStreamAddressSpace.size();
        }

        log.trace("getTxStreamTotalEntries:: entries={} in range ({}, {}]", totalEntries, lowerBoundary, upperBoundary);
        return totalEntries;
    }

    public static void shutdownTsPoller() {
        // Stop accepting any new updates
        lastAckedTsPoller.shutdown();
        try {
            // Wait 100ms for currently running tasks to finish.  If they do not finish, shutdown immediately
            if (!lastAckedTsPoller.awaitTermination(100, TimeUnit.MILLISECONDS)) {
                lastAckedTsPoller.shutdownNow();
            }
        } catch (InterruptedException e) {
            // If any task is interrupted by shutdownNow, catch that exception and shutdown immediately
            lastAckedTsPoller.shutdownNow();
        }
    }

    public void setBaseSnapshot(long baseSnapshotTimestamp) {
        this.baseSnapshotTimestamp = baseSnapshotTimestamp;
    }

    public void markSnapshotSyncInfoCompleted() {
        try {
            IRetry.build(IntervalRetry.class, () -> {
                try {
                    lock.lock();
                    metadataManager.updateSnapshotSyncStatusCompleted(session,
                            calculateRemainingEntriesToSend(baseSnapshotTimestamp), baseSnapshotTimestamp);
                } catch (TransactionAbortedException tae) {
                    log.error("Error while attempting to markSnapshotSyncInfoCompleted for remote session {}.",
                            session, tae);
                    throw new RetryNeededException();
                } finally {
                    lock.unlock();
                }

                if (log.isTraceEnabled()) {
                    log.trace("markSnapshotSyncInfoCompleted succeeds for remote session {}.", session);
                }
                return null;
            }).run();
        } catch (InterruptedException e) {
            log.error("Unrecoverable exception when attempting to markSnapshotSyncInfoCompleted.", e);
            throw new UnrecoverableCorfuInterruptedError(e);
        }
    }

    public void markSnapshotSyncInfoOngoing(boolean forced, UUID eventId) {
        try {
            IRetry.build(IntervalRetry.class, () -> {
                try {
                    lock.lock();
                    long remainingEntriesToSend = calculateRemainingEntriesToSend(lastAckedTimestamp);
                    metadataManager.updateSnapshotSyncStatusOngoing(session, forced, eventId,
                            baseSnapshotTimestamp, remainingEntriesToSend);
                } catch (TransactionAbortedException tae) {
                    log.error("Error while attempting to markSnapshotSyncInfoOngoing for event {}.", eventId, tae);
                    throw new RetryNeededException();
                } finally {
                    lock.unlock();
                }

                if (log.isTraceEnabled()) {
                    log.trace("markSnapshotSyncInfoOngoing succeeds with eventId{} and forced flag {}.", eventId, forced);
                }
                return null;
            }).run();
        } catch (InterruptedException e) {
            log.error("Unrecoverable exception when attempting to markSnapshotSyncInfoOngoing.", e);
            throw new UnrecoverableCorfuInterruptedError(e);
        }
    }

    public void markSnapshotSyncInfoOngoing() {
        try {
            IRetry.build(IntervalRetry.class, () -> {
                try {
                    lock.lock();
                    metadataManager.updateSyncStatus(session, lastSyncType, SyncStatus.ONGOING);
                } catch (TransactionAbortedException tae) {
                    log.error("Error while attempting to markSnapshotSyncInfoOngoing for session {}.", session, tae);
                    throw new RetryNeededException();
                } finally {
                    lock.unlock();
                }

                return null;
            }).run();
        } catch (InterruptedException e) {
            log.error("Unrecoverable exception when attempting to markSnapshotSyncInfoOngoing.", e);
            throw new UnrecoverableCorfuInterruptedError(e);
        }
    }

    public void markSyncStatus(SyncStatus status) {
        try {
            IRetry.build(IntervalRetry.class, () -> {
                try {
                    lock.lock();
                    metadataManager.updateSyncStatus(session, lastSyncType, status);
                } catch (TransactionAbortedException tae) {
                    log.error("Error while attempting to markSyncStatus as {}.", status, tae);
                    throw new RetryNeededException();
                } finally {
                    lock.unlock();
                }

                if (log.isTraceEnabled()) {
                    log.trace("markSyncStatus succeeds as {}.", status);
                }
                return null;
            }).run();
        } catch (InterruptedException e) {
            log.error("Unrecoverable exception when attempting to markSyncStatus as {}.", status, e);
            throw new UnrecoverableCorfuInterruptedError(e);
        }
    }

    /**
     * Start periodic replication status update task (completion percentage)
     */
    public void startSyncStatusUpdatePeriodicTask() {
        log.info("Start sync status update periodic task");
        lastAckedTsPoller.scheduleWithFixedDelay(new TsPollingTask(), 0, ACKED_TS_READ_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
    }

    /**
     * Task which periodically updates the metadata table with replication completion percentage
     */
    private class TsPollingTask implements Runnable {
        @Override
        public void run() {
            try {
                IRetry.build(IntervalRetry.class, () -> {
                    try {
                        lock.lock();
                        if (lastSyncType == null) {
                            log.info("lastSyncType is null before polling task run");
                            return null;
                        }
                        long entriesToSend = calculateRemainingEntriesToSend(lastAckedTimestamp);
                        metadataManager.updateRemainingEntriesToSend(session, entriesToSend, lastSyncType);
                    } catch (TransactionAbortedException tae) {
                        log.error("Error while attempting to set remaining entries for remote session {} with " +
                                "lastSyncType {}.", session, lastSyncType, tae);
                        throw new RetryNeededException();
                    } finally {
                        lock.unlock();
                    }

                    return null;
                }).run();
            } catch (InterruptedException e) {
                log.error("Unrecoverable exception when attempting to updateRemainingEntriesToSend", e);
                throw new UnrecoverableCorfuInterruptedError(e);
            }
        }
    }
}
