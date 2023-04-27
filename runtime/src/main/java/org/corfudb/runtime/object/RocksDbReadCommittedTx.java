package org.corfudb.runtime.object;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import lombok.NonNull;
import org.corfudb.runtime.collections.RocksDbEntryIterator;
import org.corfudb.util.serializer.ISerializer;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.OptimisticTransactionDB;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.Transaction;

import java.util.concurrent.locks.StampedLock;

/**
 * A concrete class that implements {@link RocksDbApi} using
 * {@link Transaction}. Unlike its cousin {@link RocksDbTx},
 *   1) All mutators are no-ops.
 *   2) All accessors operate directly on {@link OptimisticTransactionDB}.
 * This will effectively provide read-committed consistency.
 *
 * @param <S> extends SnapshotGenerator
 */
public class RocksDbReadCommittedTx<S extends SnapshotGenerator<S>> implements RocksDbApi<S> {
    private final OptimisticTransactionDB rocksDb;
    private final ReadOptions readOptions;
    private final RocksDbColumnFamilyRegistry cfRegistry;

    public RocksDbReadCommittedTx(@NonNull OptimisticTransactionDB rocksDb,
                                  @NonNull RocksDbColumnFamilyRegistry cfRegistry) {
        this.rocksDb = rocksDb;
        this.cfRegistry = cfRegistry;
        this.readOptions = new ReadOptions();
    }
    
    @Override
    public byte[] get(@NonNull ByteBuf keyPayload) throws RocksDBException {
        return this.rocksDb.get(readOptions, ByteBufUtil.getBytes(
                keyPayload, keyPayload.arrayOffset(), keyPayload.readableBytes(), false));
    }

    @Override
    public void insert(@NonNull ByteBuf keyPayload, @NonNull ByteBuf valuePayload) throws RocksDBException {
        // No-op.
    }

    @Override
    public void delete(@NonNull ByteBuf keyPayload) throws RocksDBException {
        // No-op
    }

    @Override
    public void clear() {
        // No-op
    }

    public long exactSize() {
        long count = 0;
        try (RocksIterator entryIterator = rocksDb.newIterator()) {
            entryIterator.seekToFirst();
            while (entryIterator.isValid()) {
                entryIterator.next();
                count++;
            }
        }
        return count;
    }

    @Override
    public void close() throws RocksDBException {
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
    public <K, V> RocksDbEntryIterator<K,V> getIterator(@NonNull ISerializer serializer) {
        return new RocksDbEntryIterator<>(rocksDb.newIterator(), serializer, readOptions, new StampedLock());
    }

    @Override
    public BatchedUpdatesAdapter getBatchedUpdatesAdapter() {
        return new WriteBatchTxStubAdapter();
    }

    private static class WriteBatchTxStubAdapter implements BatchedUpdatesAdapter {
        private boolean isProcessed;

        public WriteBatchTxStubAdapter() {
            this.isProcessed = false;
        }

        @Override
        public void insert(@NonNull ColumnFamilyHandle cfh,
                           @NonNull ByteBuf keyPayload,
                           @NonNull ByteBuf valuePayload) throws RocksDBException {

            if (isProcessed) {
                throw new IllegalStateException();
            }
        }

        @Override
        public void delete(@NonNull ColumnFamilyHandle cfh,
                           @NonNull ByteBuf keyPayload) throws RocksDBException {

            if (isProcessed) {
                throw new IllegalStateException();
            }
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
