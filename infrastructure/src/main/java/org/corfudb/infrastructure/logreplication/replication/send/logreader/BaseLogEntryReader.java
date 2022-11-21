package org.corfudb.infrastructure.logreplication.replication.send.logreader;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.TextFormat;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.corfudb.common.metrics.micrometer.MeterRegistryProvider;
import org.corfudb.infrastructure.logreplication.LogReplicationConfig;
import org.corfudb.infrastructure.logreplication.infrastructure.ReplicationSession;
import org.corfudb.infrastructure.logreplication.utils.LogReplicationConfigManager;
import org.corfudb.protocols.logprotocol.OpaqueEntry;
import org.corfudb.protocols.logprotocol.SMREntry;
import org.corfudb.runtime.CorfuRuntime;
import org.corfudb.runtime.LogReplication.LogReplicationEntryMetadataMsg;
import org.corfudb.runtime.LogReplication.LogReplicationEntryMsg;
import org.corfudb.runtime.LogReplication.LogReplicationEntryType;
import org.corfudb.runtime.exceptions.TrimmedException;
import org.corfudb.runtime.view.Address;
import org.corfudb.runtime.view.stream.OpaqueStream;

import javax.annotation.concurrent.NotThreadSafe;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.google.protobuf.UnsafeByteOperations.unsafeWrap;
import static org.corfudb.infrastructure.logreplication.LogReplicationConfig.MAX_DATA_MSG_SIZE_SUPPORTED;
import static org.corfudb.protocols.CorfuProtocolCommon.getUuidMsg;
import static org.corfudb.protocols.service.CorfuProtocolLogReplication.generatePayload;
import static org.corfudb.protocols.service.CorfuProtocolLogReplication.getLrEntryMsg;

@Slf4j
@NotThreadSafe
/**
 * Reading transaction log changes after a snapshot transfer for a specific set of streams. The set of streams to replicate
 * will be synced by the config at the start of a log entry sync and when a new stream to replicate is discovered.
 */
public class BaseLogEntryReader extends LogEntryReader {

    private final LogReplicationEntryType MSG_TYPE = LogReplicationEntryType.LOG_ENTRY_MESSAGE;

    protected final LogReplicationConfigManager configManager;

    // Set of UUIDs for the corresponding streams
    protected Set<UUID> streamsToReplicate;

    // Opaque Stream wrapper for the stream to track for a given replication model
    private ModelBasedOpaqueStream modelBasedOpaqueStream;

    // Snapshot Timestamp on which the log entry reader is based on
    private long globalBaseSnapshot;

    // Timestamp of the transaction log that is the previous message
    private long preMsgTs;

    // the sequence number of the message based on the globalBaseSnapshot
    private long sequence;

    private long topologyConfigId;

    private final int maxDataSizePerMsg;

    private final Optional<DistributionSummary> messageSizeDistributionSummary;
    private final Optional<Counter> deltaCounter;
    private final Optional<Counter> validDeltaCounter;
    private final Optional<Counter> opaqueEntryCounter;
    @Getter
    @VisibleForTesting
    private OpaqueEntry lastOpaqueEntry = null;

    private boolean lastOpaqueEntryValid = true;

    private boolean messageExceededSize = false;

    private StreamIteratorMetadata currentProcessedEntryMetadata;

    protected ReplicationSession session;

    protected LogReplicationConfig config;

    public BaseLogEntryReader(CorfuRuntime runtime, LogReplicationConfigManager configManager,
                                 ReplicationSession replicationSession) {
        runtime.parseConfigurationString(runtime.getLayoutServers().get(0)).connect();
        this.configManager = configManager;
        config = configManager.getSubscriberToConfigMap().get(replicationSession.getSubscriber());
        this.maxDataSizePerMsg = config.getMaxDataSizePerMsg();
        this.currentProcessedEntryMetadata = new StreamIteratorMetadata(Address.NON_ADDRESS, false);
        this.messageSizeDistributionSummary = configureMessageSizeDistributionSummary();
        this.deltaCounter = configureDeltaCounter();
        this.validDeltaCounter = configureValidDeltaCounter();
        this.opaqueEntryCounter = configureOpaqueEntryCounter();
        this.session = replicationSession;

        // Get UUIDs for streams to replicate
        refreshStreamsToReplicateSet();
        log.info("Total of {} streams to replicate at initialization.", this.streamsToReplicate.size());

        // Create an opaque stream for this replication model
        modelBasedOpaqueStream = new ModelBasedOpaqueStream(runtime);
    }

    /**
     * Get streams to replicate from config and convert them into stream ids. This method will be invoked at
     * constructor and when LogReplicationConfig is synced with registry table.
     */
    protected void refreshStreamsToReplicateSet() {
        config = configManager.getUpdatedConfig(session.getSubscriber());
    }

    private LogReplicationEntryMsg generateMessageWithOpaqueEntryList(
            List<OpaqueEntry> opaqueEntryList, UUID logEntryRequestId) {
        // Set the last timestamp as the max timestamp
        long currentMsgTs = opaqueEntryList.get(opaqueEntryList.size() - 1).getVersion();
        LogReplicationEntryMetadataMsg metadata = LogReplicationEntryMetadataMsg.newBuilder()
                .setEntryType(MSG_TYPE)
                .setTopologyConfigID(topologyConfigId)
                .setSyncRequestId(getUuidMsg(logEntryRequestId))
                .setTimestamp(currentMsgTs)
                .setPreviousTimestamp(preMsgTs)
                .setSnapshotTimestamp(globalBaseSnapshot)
                .setSnapshotSyncSeqNum(sequence)
                .build();

        LogReplicationEntryMsg txMessage = getLrEntryMsg(unsafeWrap(generatePayload(opaqueEntryList)), metadata);

        preMsgTs = currentMsgTs;
        sequence++;
        log.trace("Generate a log entry message {} with {} transactions ",
                TextFormat.shortDebugString(txMessage.getMetadata()), opaqueEntryList.size());
        return txMessage;
    }

    /**
     * Verify the transaction entry is valid, i.e., if the entry contains any
     * of the streams to be replicated.
     * <p>
     * Notice that a transaction stream entry can be fully or partially replicated,
     * i.e., if only a subset of streams in the transaction entry are part of the streams
     * to replicate, the transaction entry will be partially replicated,
     * avoiding replication of the other streams present in the transaction.
     *
     * @param entry transaction stream opaque entry
     * @return true, if the transaction entry has any valid stream to replicate.
     * false, otherwise.
     */
    private boolean isValidTransactionEntry(@NonNull OpaqueEntry entry) {
        Set<UUID> txEntryStreamIds = new HashSet<>(entry.getEntries().keySet());

        // Sanity Check: discard if transaction stream opaque entry is empty (no streams are present)
        if (txEntryStreamIds.isEmpty()) {
            log.debug("TX Stream entry[{}] :: EMPTY [ignored]", entry.getVersion());
            return false;
        }

        // It is possible that tables corresponding to some streams to replicate were not opened when LogReplicationConfig
        // was initialized. So these streams will be missing from the list of streams to replicate. Check the registry
        // table and add them to the list in that case.
        if (!streamsToReplicate.containsAll(txEntryStreamIds)) {
            log.info("There could be additional streams to replicate in tx stream. Checking with registry table.");
            config = configManager.getUpdatedConfig(session.getSubscriber());
            refreshStreamsToReplicateSet();
            // TODO: Add log message here for the newly found streams when we support incremental refresh.
        }
        // If none of the streams in the transaction entry are specified to be replicated, this is an invalid entry, skip
        if (Collections.disjoint(streamsToReplicate, txEntryStreamIds)) {
            log.trace("TX Stream entry[{}] :: contains none of the streams of interest, streams={} [ignored]", entry.getVersion(), txEntryStreamIds);
            return false;
        } else {
            Set<UUID> ignoredTxStreams = txEntryStreamIds.stream().filter(id -> !streamsToReplicate.contains(id))
                    .collect(Collectors.toSet());
            txEntryStreamIds.removeAll(ignoredTxStreams);
            log.debug("TX Stream entry[{}] :: replicate[{}]={}, ignore[{}]={} [valid]", entry.getVersion(),
                    txEntryStreamIds.size(), txEntryStreamIds, ignoredTxStreams.size(), ignoredTxStreams);
            return true;
        }
    }

    private boolean checkValidSize(int currentMsgSize, int currentEntrySize) {
        // For interested entry, if its size is too big we should skip and report error
        if (currentEntrySize > maxDataSizePerMsg) {
            log.error("The current entry size {} is bigger than the maxDataSizePerMsg {} supported.",
                    currentEntrySize, MAX_DATA_MSG_SIZE_SUPPORTED);
            // If a message cannot be sent due to its size exceeding the maximum boundary, the replication will be stopped.
            messageExceededSize = true;
            return false;
        }

        // If it exceeds the maximum size of this message, skip appending this entry,
        // it will be processed with the next message;
        return currentEntrySize + currentMsgSize <= maxDataSizePerMsg;
    }

    public void setGlobalBaseSnapshot(long snapshot, long ackTimestamp) {
        globalBaseSnapshot = snapshot;
        preMsgTs = Math.max(snapshot, ackTimestamp);
        log.info("snapshot {} ackTimestamp {} preMsgTs {} seek {}", snapshot, ackTimestamp, preMsgTs, preMsgTs + 1);
        modelBasedOpaqueStream.seek(preMsgTs + 1);
        sequence = 0;
    }

    @Override
    public LogReplicationEntryMsg read(UUID logEntryRequestId) throws TrimmedException {
        List<OpaqueEntry> opaqueEntryList = new ArrayList<>();
        int currentEntrySize = 0;
        int currentMsgSize = 0;

        try {
            while (currentMsgSize < maxDataSizePerMsg) {
                if (lastOpaqueEntry != null) {

                    if (lastOpaqueEntryValid) {

                        lastOpaqueEntry = filterTransactionEntry(lastOpaqueEntry);

                        // If the currentEntry is too big to append the current message, will skip it and
                        // append it to the next message as the first entry.
                        currentEntrySize = ReaderUtility.calculateOpaqueEntrySize(lastOpaqueEntry);

                        if (!checkValidSize(currentMsgSize, currentEntrySize)) {
                            break;
                        }

                        // Add the lastOpaqueEntry to the current message.
                        opaqueEntryList.add(lastOpaqueEntry);
                        currentMsgSize += currentEntrySize;
                    }

                    lastOpaqueEntry = null;
                }
                if (!modelBasedOpaqueStream.hasNext()) {
                    break;
                }

                lastOpaqueEntry = modelBasedOpaqueStream.next();
                deltaCounter.ifPresent(Counter::increment);
                lastOpaqueEntryValid = isValidTransactionEntry(lastOpaqueEntry);
                if (lastOpaqueEntryValid) {
                    validDeltaCounter.ifPresent(Counter::increment);
                }
                currentProcessedEntryMetadata = new StreamIteratorMetadata(modelBasedOpaqueStream.opaqueStream.pos(),
                    lastOpaqueEntryValid);
            }

            log.trace("Generate LogEntryDataMessage size {} with {} entries for maxDataSizePerMsg {}. lastEntry size {}",
                currentMsgSize, opaqueEntryList.size(), maxDataSizePerMsg, lastOpaqueEntry == null ? 0 : currentEntrySize);
            final double currentMsgSizeSnapshot = currentMsgSize;

            messageSizeDistributionSummary.ifPresent(distribution -> distribution.record(currentMsgSizeSnapshot));

            opaqueEntryCounter.ifPresent(counter -> counter.increment(opaqueEntryList.size()));

            if (opaqueEntryList.isEmpty()) {
                return null;
            }

            LogReplicationEntryMsg txMessage = generateMessageWithOpaqueEntryList(
                    opaqueEntryList, logEntryRequestId);
            return txMessage;

        } catch (Exception e) {
            log.warn("Caught an exception while reading transaction stream {}", e);
            throw e;
        }
    }

    /**
     * Filter out streams that are not intended for replication
     *
     * @param opaqueEntry opaque entry to parse.
     * @return filtered opaque entry
     */
    private OpaqueEntry filterTransactionEntry(OpaqueEntry opaqueEntry) {
        Map<UUID, List<SMREntry>> filteredTxEntryMap = opaqueEntry.getEntries().entrySet().stream()
                .filter(entry -> streamsToReplicate.contains(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        return new OpaqueEntry(opaqueEntry.getVersion(), filteredTxEntryMap);
    }

    private Optional<DistributionSummary> configureMessageSizeDistributionSummary() {
        return MeterRegistryProvider.getInstance().map(registry ->
                DistributionSummary.builder("logreplication.message.size.bytes")
                        .baseUnit("bytes")
                        .tags("replication.type", "logentry")
                        .register(registry));
    }

    private Optional<Counter> configureDeltaCounter() {
        return MeterRegistryProvider.getInstance().map(registry ->
                registry.counter("logreplication.opaque.count_total"));
    }

    private Optional<Counter> configureValidDeltaCounter() {
        return MeterRegistryProvider.getInstance().map(registry ->
                registry.counter("logreplication.opaque.count_valid"));
    }

    private Optional<Counter> configureOpaqueEntryCounter() {
        return MeterRegistryProvider.getInstance().map(registry ->
                registry.counter("logreplication.opaque.count_per_message"));
    }

    @Override
    public void reset(long lastSentBaseSnapshotTimestamp, long lastAckedTimestamp) {
        // Sync with registry when entering into IN_LOG_ENTRY_SYNC state
        refreshStreamsToReplicateSet();
        messageExceededSize = false;
        this.currentProcessedEntryMetadata = new StreamIteratorMetadata(Address.NON_ADDRESS, false);
        setGlobalBaseSnapshot(lastSentBaseSnapshotTimestamp, lastAckedTimestamp);
        lastOpaqueEntry = null;
    }

    @Override
    public StreamIteratorMetadata getCurrentProcessedEntryMetadata() {
        return currentProcessedEntryMetadata;
    }


    @Override
    public void setTopologyConfigId(long topologyConfigId) {
        this.topologyConfigId = topologyConfigId;
    }

    /**
     * The class used to track the opaque stream based on replication model
     */
    private class ModelBasedOpaqueStream {
        private CorfuRuntime rt;
        private OpaqueStream opaqueStream;
        private Iterator iterator;

        ModelBasedOpaqueStream(CorfuRuntime rt) {
            //create an opaque stream for the model
            this.rt = rt;
            opaqueStream = new OpaqueStream(rt.getStreamsView().get(configManager.getOpaqueStreamToTrack(session)));
            streamUpTo();
        }

        /**
         * Set the iterator with entries from current seekAddress till snapshot
         *
         * @param snapshot
         */
        private void streamUpTo(long snapshot) {
            log.trace("StreamUpTo {}", snapshot);
            iterator = opaqueStream.streamUpTo(snapshot).iterator();
        }

        /**
         * Set the iterator with entries from current seekAddress till end of the log tail
         */
        private void streamUpTo() {
            streamUpTo(rt.getAddressSpaceView().getLogTail());
        }

        /**
         * Tell if the transaction stream has the next entry
         */
        private boolean hasNext() {
            if (!iterator.hasNext()) {
                streamUpTo();
            }
            return iterator.hasNext();
        }

        /**
         * Get the next entry from the transaction stream.
         */
        private OpaqueEntry next() {
            if (!hasNext())
                return null;

            OpaqueEntry opaqueEntry = (OpaqueEntry) iterator.next();
            log.trace("Address {} OpaqueEntry {}", opaqueEntry.getVersion(), opaqueEntry);
            return opaqueEntry;
        }

        /**
         * Set stream head as firstAddress, set the iterator from
         * firstAddress till the current tail of the log
         *
         * @param firstAddress
         */
        public void seek(long firstAddress) {
            log.trace("seek head {}", firstAddress);
            opaqueStream.seek(firstAddress);
            streamUpTo();
        }
    }
}
