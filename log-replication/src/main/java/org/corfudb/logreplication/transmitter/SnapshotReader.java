package org.corfudb.logreplication.transmitter;

/**
 * An Interface for snapshot reader.
 *
 * A snapshot reader provides the functionality for reading data from Corfu.
 */
public interface SnapshotReader {

    /**
     * Read streams to replicate across sites.
     *
     * @return result of read operation.
     */
    SnapshotReadMessage read();

    /**
     * Reset reader in between snapshot syncs.
     *
     * @param snapshotTimestamp new snapshot timestamp.
     */
    void reset(long snapshotTimestamp);
}
