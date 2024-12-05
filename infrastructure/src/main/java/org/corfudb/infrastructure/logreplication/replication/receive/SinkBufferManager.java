package org.corfudb.infrastructure.logreplication.replication.receive;

import lombok.extern.slf4j.Slf4j;
import org.corfudb.runtime.LogReplication.LogReplicationEntryMetadataMsg;
import org.corfudb.runtime.LogReplication.LogReplicationEntryMsg;
import org.corfudb.runtime.LogReplication.LogReplicationEntryType;

import java.util.HashMap;

import static org.corfudb.protocols.service.CorfuProtocolLogReplication.getLrEntryAckMsg;

/**
 * For snapshot sync and log entry sync, it is possible that the messages generated by the primary cluster will
 * be delivered out of order due to message loss due to network connect loss or congestion.
 * At the backup/receiver cluster we keep a buffer to store the out-of-order messages and apply them in order.
 * For snapshot sync, the message will be applied according to the message's snapshotSeqNumber.
 * For log entry sync, each message has a pre pointer that is a timestamp of the previous message, this guarantees that
 * the messages will be applied in order.
 *
 * At the same time, it still sends an ACK to the primary cluster to notify any possible data loss.
 */
@Slf4j
public abstract class SinkBufferManager {

    /*
     * The buffer is implemented as a hashmap.
     * For logEntry buffer, the key is the entry's previousTimeStamp
     * For Snapshot buffer, the key is the previous entry's snapshotSeqNumber
     */
    public HashMap<Long, LogReplicationEntryMsg> buffer;

    /*
     * While processing a message in the buffer, it will call
     * sinkManager to handle it.
     */
    public LogReplicationSinkManager sinkManager;

    /*
     * Could be LOG_ENTRY or SNAPSHOT
     */
    public LogReplicationEntryType type;

    /*
     * The max number of entries in the buffer.
     */
    public int maxSize;

    /*
     * How frequent in time, the ack will be sent.
     */
    private int ackCycleTime;

    /*
     * How frequent in number of messages it has received.
     */
    private int ackCycleCnt;

    /*
     * Count the number of messages it has received since last sent ACK.
     */
    public int ackCnt = 0;

    /*
     * Time last ack sent.
     */
    public long ackTime = 0;

    /*
     * The lastProcessedSeq message's ack value.
     * For snapshot, it is the entry's seqNumber.
     * For log entry, it is the entry's timestamp.
     */
    public long lastProcessedSeq;

    /**
     *
     * @param type
     * @param ackCycleTime
     * @param ackCycleCnt
     * @param size
     * @param lastProcessedSeq
     * @param sinkManager
     */
    public SinkBufferManager(LogReplicationEntryType type,
                             int ackCycleTime, int ackCycleCnt,
                             int size, long lastProcessedSeq,
                             LogReplicationSinkManager sinkManager) {
        this.type = type;
        this.ackCycleTime = ackCycleTime;
        this.ackCycleCnt = ackCycleCnt;
        this.maxSize = size;
        this.sinkManager = sinkManager;
        this.lastProcessedSeq = lastProcessedSeq;
        buffer = new HashMap<>();
    }

    /**
     * After receiving a message, it will decide to send an Ack or not
     * according to the predefined metrics.
     *
     * @return
     */
    public boolean shouldAck() {
        long currentTime = java.lang.System.currentTimeMillis();
        ackCnt++;

        if (ackCnt >= ackCycleCnt || (currentTime - ackTime) >= ackCycleTime) {
            ackCnt = 0;
            ackTime = currentTime;
            return true;
        }

        return false;
    }

    /**
     * If the message is the expected message in order, will skip the buffering and pass to sinkManager to process it;
     * then update the lastProcessedSeq value. If the next expected messages in order are in the buffer,
     * will process all till hitting the missing one.
     *
     * If the message is not the expected message, put the entry into the buffer if there is space.
     * @param dataMessage
     */
    public LogReplicationEntryMsg processMsgAndBuffer(LogReplicationEntryMsg dataMessage) {

        if (!verifyMessageType(dataMessage)) {
            log.warn("Received invalid message type {}", dataMessage.getMetadata());
            return null;
        }

        long preTs = getPreSeq(dataMessage);
        long currentTs = getCurrentSeq(dataMessage);

        // This message contains entries that haven't been applied yet
        if (preTs <= lastProcessedSeq && lastProcessedSeq < currentTs) {
            // The received message is either the next in sequence OR the same message as the last one with a few
            // more opaque entries.
            log.debug("Received in order message={}, lastProcessed={}", currentTs, lastProcessedSeq);
            if (sinkManager.processMessage(dataMessage)) {
                ackCnt++;
                lastProcessedSeq = getCurrentSeq(dataMessage);
            }
            processBuffer();
        } else if (currentTs > lastProcessedSeq && buffer.size() < maxSize) {
            log.debug("Received unordered message, currentTs={}, lastProcessed={}", currentTs, lastProcessedSeq);
            buffer.put(preTs, dataMessage);
        }

        /*
         * Send Ack with lastProcessedSeq
         */
        if (shouldAck()) {
            //TODO: we create an ACK using the metadata of the last recevied msg, and then override
            // the timestamp with lastProcessedTs. So the rest of the ACK metadata does not match
            // with the ACK.Timestamp().
            // Currently its fine since active only consumes the ACK.timestamp()...but if the behaviour
            // changes on active, we need to change it here as well
            LogReplicationEntryMetadataMsg metadata = generateAckMetadata(dataMessage);
            log.trace("Sending an ACK {}", metadata);
            return getLrEntryAckMsg(metadata);
        }

        return null;
    }

    // Process messages in the buffer that are in order
    public abstract void processBuffer();

    /**
     * Get the previous in order message's sequence.
     * @param entry
     * @return
     */
    public abstract long getPreSeq(LogReplicationEntryMsg entry);

    /**
     * Get the current message's sequence.
     * @param entry
     * @return
     */
    public abstract long getCurrentSeq(LogReplicationEntryMsg entry);

    /**
     * Make an Ack with the lastProcessedSeq
     * @param entry
     * @return
     */
    public abstract LogReplicationEntryMetadataMsg generateAckMetadata(LogReplicationEntryMsg entry);

    /*
     * Verify if the message is the correct type.
     * @param entry
     * @return
     */
    public abstract boolean verifyMessageType(LogReplicationEntryMsg entry);
}
