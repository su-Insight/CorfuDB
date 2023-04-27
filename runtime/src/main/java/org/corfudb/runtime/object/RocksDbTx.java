package org.corfudb.runtime.object;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import lombok.NonNull;
import org.corfudb.runtime.collections.RocksDbEntryIterator;
import org.corfudb.runtime.exceptions.unrecoverable.UnrecoverableCorfuError;
import org.corfudb.util.serializer.ISerializer;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.OptimisticTransactionDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.Transaction;
import org.rocksdb.WriteOptions;

/**
 * A concrete class that implements {@link RocksDbApi} using
 * {@link Transaction}.
 *
 * @param <S> extends SnapshotGenerator
 */
public class RocksDbTx<S extends SnapshotGenerator<S>> implements RocksDbApi<S> {

    private final DiskBackedSMRSnapshot<S> snapshot;
    private final Transaction txn;
    private final RocksDbColumnFamilyRegistry cfRegistry;

    public RocksDbTx(@NonNull OptimisticTransactionDB rocksDb,
                     @NonNull WriteOptions writeOptions,
                     @NonNull DiskBackedSMRSnapshot<S> snapshot,
                     @NonNull RocksDbColumnFamilyRegistry cfRegistry) {
        this.snapshot = snapshot;
        this.cfRegistry = cfRegistry;
        this.txn = rocksDb.beginTransaction(writeOptions);
    }

    @Override
    public byte[] get(@NonNull ByteBuf keyPayload) throws RocksDBException {
        return snapshot.executeInSnapshot(readOptions -> {
            try {
                return txn.get(readOptions, ByteBufUtil.getBytes(
                        keyPayload, keyPayload.arrayOffset(), keyPayload.readableBytes(), false));
            } catch (RocksDBException e) {
                throw new UnrecoverableCorfuError(e);
            }
        });
    }

    @Override
    public void insert(@NonNull ByteBuf keyPayload, @NonNull ByteBuf valuePayload) throws RocksDBException {
        // https://javadoc.io/static/org.rocksdb/rocksdbjni/7.9.2/org/rocksdb/Transaction.html#putUntracked-byte:A-byte:A-
        // No conflict checking since this context is always aborted.
        txn.putUntracked(
                ByteBufUtil.getBytes(keyPayload, keyPayload.arrayOffset(), keyPayload.readableBytes(), false),
                ByteBufUtil.getBytes(valuePayload, valuePayload.arrayOffset(), valuePayload.readableBytes(), false)
        );
    }

    @Override
    public void delete(@NonNull ByteBuf keyPayload) throws RocksDBException {
        // https://javadoc.io/static/org.rocksdb/rocksdbjni/7.9.2/org/rocksdb/Transaction.html#deleteUntracked-byte:A-
        // No conflict checking since this context is always aborted.
        txn.deleteUntracked(
                ByteBufUtil.getBytes(keyPayload, keyPayload.arrayOffset(), keyPayload.readableBytes(), false)
        );
    }

    @Override
    public <K, V> RocksDbEntryIterator<K, V> getIterator(@NonNull ISerializer serializer) {
        return this.snapshot.newIterator(serializer, txn);
    }

    public void prefixScan(String cfName, ByteBuf keyPrefix) {
        // Retrieve the ColumnFamilyHandle associated with this secondary index.
        ColumnFamilyHandle cfh = cfRegistry.get(cfName);
        byte[] prefix = ByteBufUtil.getBytes(keyPrefix, 0, 4, false);

        snapshot.executeInSnapshot(readOptions -> {
            try (RocksIterator entryIterator = txn.getIterator(readOptions)) {
                entryIterator.seek(keyPrefix.nioBuffer());
                while (entryIterator.isValid()) {
                    byte[] serializedKey = entryIterator.key();

                    // TODO:

                    entryIterator.next();
                }
            } catch (Exception e) {
                // TODO:
            }
        });
    }

    @Override
    public void clear() throws RocksDBException {
        snapshot.executeInSnapshot(readOptions -> {
            try {
                // Access RocksIterator directly to avoid the cost of (de)serialization.
                try (RocksIterator entryIterator = txn.getIterator(readOptions)) {
                    entryIterator.seekToFirst();
                    while (entryIterator.isValid()) {
                        txn.delete(entryIterator.key());
                        entryIterator.next();
                    }
                }
            } catch (RocksDBException e) {
                throw new UnrecoverableCorfuError(e);
            }
        });
    }

    @Override
    public long exactSize() {
        return snapshot.executeInSnapshot(readOptions -> {
            long count = 0;
            // Access RocksIterator directly to avoid the cost of (de)serialization.
            try (RocksIterator entryIterator = txn.getIterator(readOptions)) {
                entryIterator.seekToFirst();
                while (entryIterator.isValid()) {
                    entryIterator.next();
                    count++;
                }
            }
            return count;
        });
    }

    @Override
    public void close() throws RocksDBException {
        txn.rollback();
    }

    /**
     * Return the registry of column families associated with
     * this RocksDbStore instance.
     * @return The associated registry of column families.
     */
    @Override
    public RocksDbColumnFamilyRegistry getRegisteredColumnFamilies() {
        return cfRegistry;
    }

    @Override
    public BatchedUpdatesAdapter getBatchedUpdatesAdapter() {
        return new WriteBatchTxAdapter(txn);
    }

    private static class WriteBatchTxAdapter implements BatchedUpdatesAdapter {
        private final Transaction txn;

        private boolean isProcessed;

        public WriteBatchTxAdapter(@NonNull Transaction txn) {
            this.txn = txn;
            this.isProcessed = false;
        }

        @Override
        public void insert(@NonNull ColumnFamilyHandle cfh,
                           @NonNull ByteBuf keyPayload,
                           @NonNull ByteBuf valuePayload) throws RocksDBException {

            if (isProcessed) {
                throw new IllegalStateException();
            }

            // No conflict checking since the underlying txn is always aborted.
            txn.putUntracked(cfh,
                    ByteBufUtil.getBytes(keyPayload, keyPayload.arrayOffset(), keyPayload.readableBytes(), false),
                    ByteBufUtil.getBytes(valuePayload, valuePayload.arrayOffset(), valuePayload.readableBytes(), false)
            );
        }

        @Override
        public void delete(@NonNull ColumnFamilyHandle cfh,
                           @NonNull ByteBuf keyPayload) throws RocksDBException {

            if (isProcessed) {
                throw new IllegalStateException();
            }

            // No conflict checking since the underlying txn is always aborted.
            txn.deleteUntracked(cfh,
                    ByteBufUtil.getBytes(keyPayload, keyPayload.arrayOffset(), keyPayload.readableBytes(), false)
            );
        }

        @Override
        public void process() throws RocksDBException {
            isProcessed = true;
        }

        @Override
        public void close() {
            // No-op.
        }
    }
}
