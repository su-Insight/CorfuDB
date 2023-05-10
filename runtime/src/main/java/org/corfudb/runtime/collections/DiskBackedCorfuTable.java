package org.corfudb.runtime.collections;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import io.micrometer.core.instrument.Timer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.corfudb.common.metrics.micrometer.MeterRegistryProvider;
import org.corfudb.common.metrics.micrometer.MicroMeterUtils;
import org.corfudb.runtime.CorfuOptions;
import org.corfudb.runtime.exceptions.unrecoverable.UnrecoverableCorfuError;
import org.corfudb.runtime.object.ColumnFamilyRegistry;
import org.corfudb.runtime.object.ConsistencyView;
import org.corfudb.runtime.object.PersistenceOptions;
import org.corfudb.runtime.object.RocksDbApi;
import org.corfudb.runtime.object.RocksDbSnapshotGenerator;
import org.corfudb.runtime.object.SMRSnapshot;
import org.corfudb.runtime.object.RocksDbStore;
import org.corfudb.runtime.object.SnapshotGenerator;
import org.corfudb.runtime.object.VersionedObjectIdentifier;
import org.corfudb.runtime.object.ViewGenerator;
import org.corfudb.runtime.view.ObjectOpenOption;
import org.corfudb.util.serializer.ISerializer;
import org.corfudb.util.serializer.ProtobufSerializer;
import org.rocksdb.CompressionType;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.Statistics;
import org.rocksdb.StatsLevel;
import org.rocksdb.WriteOptions;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.corfudb.runtime.CorfuOptions.ConsistencyModel.READ_COMMITTED;

@Slf4j
@AllArgsConstructor
@Builder(toBuilder=true)
public class DiskBackedCorfuTable<K, V> implements
        SnapshotGenerator<DiskBackedCorfuTable<K, V>>,
        ViewGenerator<DiskBackedCorfuTable<K, V>>,
        ConsistencyView {

    private static final HashFunction murmurHash3 = Hashing.murmur3_32();
    public static final String DISK_BACKED = "diskBacked";
    public static final String TRUE = "true";
    public static final int BOUND = 100;
    public static final int SAMPLING_RATE = 40;

    private final WriteOptions writeOptions = new WriteOptions()
            .setDisableWAL(true)
            .setSync(false);

    static {
        RocksDB.loadLibrary();
    }

    /**
     * A set of options defined for {@link DiskBackedCorfuTable}.
     *
     * For a set of options that dictate RocksDB memory usage can be found here:
     * https://github.com/facebook/rocksdb/wiki/Memory-usage-in-RocksDB
     *
     * Block Cache:  Which can be set via Options::setTableFormatConfig.
     *               Out of box, RocksDB will use LRU-based block cache
     *               implementation with 8MB capacity.
     * Index/Filter: Is a function of the block cache. Generally it inflates
     *               the block cache by about 50%. The exact number can be
     *               retrieved via "rocksdb.estimate-table-readers-mem"
     *               property.
     * Write Buffer: Also known as memtable is defined by the ColumnFamilyOptions
     *               option. The default is 64 MB.
     */
    public static Options getDiskBackedCorfuTableOptions() {
        final Options options = new Options();

        options.setCreateIfMissing(true);
        options.setCompressionType(CompressionType.LZ4_COMPRESSION);

        return options;
    }

    private final PersistenceOptions persistenceOptions;
    private final ISerializer serializer;
    private final RocksDbApi<DiskBackedCorfuTable<K, V>> rocksApi;
    private final RocksDbSnapshotGenerator<DiskBackedCorfuTable<K, V>> rocksDbSnapshotGenerator;
    private final ColumnFamilyRegistry columnFamilyRegistry;

    private final String metricsId;
    @Getter
    private final Statistics statistics;

    private final Map<String, String> secondaryIndexesAliasToPath;
    private final Map<String, Byte> indexToId;
    private final Set<Index.Spec<K, V, ?>> indexSpec;

    public DiskBackedCorfuTable(@NonNull PersistenceOptions persistenceOptions,
                                @NonNull Options rocksDbOptions,
                                @NonNull ISerializer serializer,
                                @Nonnull Index.Registry<K, V> indices) {

        this.persistenceOptions = persistenceOptions;
        this.secondaryIndexesAliasToPath = new HashMap<>();
        this.indexToId = new HashMap<>();
        this.indexSpec = new HashSet<>();

        byte indexId = 0;
        for (Index.Spec<K, V, ?> index: indices) {
            this.secondaryIndexesAliasToPath.put(index.getAlias().get(), index.getName().get());
            this.indexSpec.add(index);
            this.indexToId.put(index.getName().get(), indexId++);
        }


        try {
            this.statistics = new Statistics();
            this.statistics.setStatsLevel(StatsLevel.ALL);
            rocksDbOptions.setStatistics(statistics);

            final RocksDbStore<DiskBackedCorfuTable<K, V>> rocksDbStore = new RocksDbStore<>(
                    persistenceOptions.getDataPath(), rocksDbOptions, writeOptions);

            this.rocksApi = rocksDbStore;
            this.columnFamilyRegistry = rocksDbStore;
            this.rocksDbSnapshotGenerator = rocksDbStore;
            this.metricsId = String.format("%s.%s.",
                    persistenceOptions.getDataPath().getFileName(), System.identityHashCode(this));
            MeterRegistryProvider.registerExternalSupplier(metricsId, this.statistics::toString);
        } catch (RocksDBException e) {
            throw new UnrecoverableCorfuError(e);
        }

        this.serializer = serializer;
    }

    public DiskBackedCorfuTable(@NonNull PersistenceOptions persistenceOptions,
                                @NonNull Options rocksDbOptions,
                                @NonNull ISerializer serializer) {
        this(persistenceOptions, rocksDbOptions, serializer, Index.Registry.empty());
    }

    public DiskBackedCorfuTable(@NonNull PersistenceOptions persistenceOptions,
                                @NonNull ISerializer serializer) {
        this(persistenceOptions, getDiskBackedCorfuTableOptions(), serializer);
    }

    public V get(@NonNull Object key) {
        Optional<Timer.Sample> recordSample = MicroMeterUtils.startTimer(
                SAMPLING_RATE > ThreadLocalRandom.current().nextInt(BOUND));

        final ByteBuf keyPayload = Unpooled.buffer();

        try {
            // Serialize in the try-catch block to release ByteBuf when an exception occurs.
            serializer.serialize(key, keyPayload);
            byte[] value = rocksApi.get(columnFamilyRegistry.getDefaultColumnFamily(), keyPayload);
            if (value == null) {
                return null;
            }
            return (V) serializer.deserialize(Unpooled.wrappedBuffer(value), null);
        } catch (RocksDBException ex) {
            throw new UnrecoverableCorfuError(ex);
        } finally {
            keyPayload.release();
            MicroMeterUtils.time(recordSample, "corfu_table.read.timer", DISK_BACKED, TRUE);
        }
    }

    public boolean containsKey(@NonNull Object key) {
        final ByteBuf keyPayload = Unpooled.buffer();

        try {
            // Serialize in the try-catch block to release ByteBuf when an exception occurs.
            serializer.serialize(key, keyPayload);
            byte[] value = rocksApi.get(columnFamilyRegistry.getDefaultColumnFamily(), keyPayload);
            return value != null;
        } catch (RocksDBException ex) {
            throw new UnrecoverableCorfuError(ex);
        } finally {
            keyPayload.release();
        }
    }

    public DiskBackedCorfuTable<K, V> put(@NonNull K key, @NonNull V value) {
        Optional<Timer.Sample> recordSample = MicroMeterUtils.startTimer(
                SAMPLING_RATE > ThreadLocalRandom.current().nextInt(BOUND));

        final ByteBuf keyPayload = Unpooled.buffer();
        final ByteBuf valuePayload = Unpooled.buffer();

        try {
            // Serialize in the try-catch block to release ByteBuf when an exception occurs.
            serializer.serialize(key, keyPayload);
            serializer.serialize(value, valuePayload);

            if (!indexSpec.isEmpty()) {
                V previous = get(key);

                // Update secondary indexes with new mappings.
                unmapSecondaryIndexes(key, previous);
                mapSecondaryIndexes(key, value);
            }

            // Insert the primary key-value mapping into the default column family.
            rocksApi.insert(columnFamilyRegistry.getDefaultColumnFamily(), keyPayload, valuePayload);
            return this;
        } catch (RocksDBException ex) {
            throw new UnrecoverableCorfuError(ex);
        } finally {
            keyPayload.release();
            valuePayload.release();
            MicroMeterUtils.time(recordSample, "corfu_table.write.timer", DISK_BACKED, TRUE);
        }
    }

    public DiskBackedCorfuTable<K, V> remove(@NonNull K key) {
        final ByteBuf keyPayload = Unpooled.buffer();

        try {
            // Serialize in the try-catch block to release ByteBuf when an exception occurs.
            serializer.serialize(key, keyPayload);

            if (!indexSpec.isEmpty()) {
                V previous = get(key);
                // Remove stale secondary indexes mappings.
                unmapSecondaryIndexes(key, previous);
            }

            // Delete the primary key-value mapping from the default column family.
            rocksApi.delete(columnFamilyRegistry.getDefaultColumnFamily(), keyPayload);
            return this;
        } catch (RocksDBException ex) {
            throw new UnrecoverableCorfuError(ex);
        } finally {
            keyPayload.release();
        }
    }

    private ByteBuf getCompoundKey(byte indexId, Object secondaryKey, K primaryKey) {
        final ByteBuf compositeKey = Unpooled.buffer();

        // Write the index ID (1 byte).
        compositeKey.writeByte(indexId);
        final int hashStart = compositeKey.writerIndex();

        // Move the index beyond the hash (4 bytes).
        compositeKey.writerIndex(compositeKey.writerIndex() + Integer.BYTES);

        // Serialize and write the secondary key and save the offset.
        final int secondaryStart = compositeKey.writerIndex();
        serializer.serialize(secondaryKey, compositeKey);
        final int secondaryLength = compositeKey.writerIndex() - secondaryStart;

        // Serialize and write the primary key and save the offset.
        serializer.serialize(primaryKey, compositeKey);
        final int end = compositeKey.writerIndex();

        // Move the pointer to the hash offset.
        compositeKey.writerIndex(hashStart);
        compositeKey.writeInt(hashBytes(compositeKey.array(), secondaryStart, secondaryLength));

        // Move the pointer to the end.
        compositeKey.writerIndex(end);

        return compositeKey;
    }

    private void unmapSecondaryIndexes(@NonNull K primaryKey, @NonNull V value) throws RocksDBException {
        try {
            for (Index.Spec<K, V, ?> index : indexSpec) {
                Iterable<?> mappedValues = index.getMultiValueIndexFunction().apply(primaryKey, value);
                for (Object secondaryKey : mappedValues) {
                    if (Objects.isNull(secondaryKey)) {
                        log.warn("{}: null secondary keys are not supported.", index.getName());
                        continue;
                    }

                    final ByteBuf serializedCompoundKey = getCompoundKey(
                            indexToId.get(index.getName().get()), secondaryKey, primaryKey);
                    try {
                        rocksApi.delete(columnFamilyRegistry.getSecondaryIndexColumnFamily(), serializedCompoundKey);
                    } finally {
                        serializedCompoundKey.release();
                    }
                }
            }
        } catch (Exception fatal) {
            log.error("Received an exception while computing the index. " +
                    "This is most likely an issue with the client's indexing function.", fatal);

            close(); // Do not leave the table in an inconsistent state.

            // In case of both a transactional and non-transactional operation, the client
            // is going to receive UnrecoverableCorfuError along with the appropriate cause.
            throw fatal;
        }
    }

    private void mapSecondaryIndexes(@NonNull K primaryKey, @NonNull V value) throws RocksDBException {
        try {
            for (Index.Spec<K, V, ?> index : indexSpec) {
                Iterable<?> mappedValues = index.getMultiValueIndexFunction().apply(primaryKey, value);
                for (Object secondaryKey : mappedValues) {
                    if (Objects.isNull(secondaryKey)) {
                        log.warn("{}: null secondary keys are not supported.", index.getName());
                        continue;
                    }

                    final ByteBuf serializedIndexValue = Unpooled.buffer();
                    final ByteBuf serializedCompoundKey = getCompoundKey(
                            indexToId.get(index.getName().get()), secondaryKey, primaryKey);
                    try {
                        rocksApi.insert(columnFamilyRegistry.getSecondaryIndexColumnFamily(),
                                serializedCompoundKey, serializedIndexValue);
                    } finally {
                        serializedCompoundKey.release();
                        serializedIndexValue.release();
                    }
                }
            }
        } catch (Exception fatal) {
            log.error("Received an exception while computing the index. " +
                    "This is most likely an issue with the client's indexing function.", fatal);

            close(); // Do not leave the table in an inconsistent state.

            // In case of both a transactional and non-transactional operation, the client
            // is going to receive UnrecoverableCorfuError along with the appropriate cause.
            throw fatal;
        }
    }

    public static int hashBytes(byte[] serializedObject, int offset, int length) {
        return murmurHash3.hashBytes(serializedObject, offset, length).asInt();
    }

    /**
     * Ideally, all serializers should be able to deal with any data type
     * thrown at it. This is not the case with {@link ProtobufSerializer},
     * which does not understand primitive data types.
     *
     * @param serializer default serializer that has been provided
     * @param object the object to serialize
     * @param target where to serialize the object
     */
    public static void serialize(ISerializer serializer, Object object, ByteBuf target) {
    }

    public DiskBackedCorfuTable<K, V> clear() {
        try {
            rocksApi.clear();
        } catch (RocksDBException e) {
            throw new UnrecoverableCorfuError(e);
        }
        return this;
    }

    public Stream<Map.Entry<K, V>> entryStream() {
        final RocksDbEntryIterator<K, V> entryIterator = rocksApi.getIterator(serializer);
        Stream<Map.Entry<K, V>> resStream = StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(entryIterator, Spliterator.ORDERED), false);
        return resStream.onClose(entryIterator::close);
    }

    public long size() {
        return rocksApi.exactSize();
    }

    public <I> Iterable<Map.Entry<K, V>> getByIndex(@Nonnull final Index.Name indexName, I indexKey) {
        String secondaryIndex = indexName.get();
        if (!secondaryIndexesAliasToPath.containsKey(secondaryIndex)) {
            return null;
        }
        byte indexId = indexToId.get(secondaryIndexesAliasToPath.get(secondaryIndex));
        Set<ByteBuf> keys = rocksApi.prefixScan(
                columnFamilyRegistry.getSecondaryIndexColumnFamily(), indexId, indexKey, serializer);

        Set<Map.Entry<K, V>> result = new HashSet<>();
        for (ByteBuf key: keys) {
            K sKey = (K) serializer.deserialize(key, null);
            result.add(new AbstractMap.SimpleEntry<>(sKey, get(sKey)));
        }

        return result;
    }

    @Override
    public void close() {
        try {
            rocksApi.close();
        } catch (RocksDBException e) {
            throw new UnrecoverableCorfuError(e);
        } finally {
            if (isRoot()) {
                MeterRegistryProvider.unregisterExternalSupplier(metricsId);
            }
        }
    }

    @Override
    public SMRSnapshot<DiskBackedCorfuTable<K, V>> generateSnapshot(VersionedObjectIdentifier version) {
        if (persistenceOptions.getConsistencyModel() == READ_COMMITTED) {
            return rocksDbSnapshotGenerator.getImplicitSnapshot(this, version);
        }
        return rocksDbSnapshotGenerator.getSnapshot(this, version);
    }

    @Override
    public Optional<SMRSnapshot<DiskBackedCorfuTable<K, V>>> generateTargetSnapshot(
            VersionedObjectIdentifier version,
            ObjectOpenOption objectOpenOption,
            SMRSnapshot<DiskBackedCorfuTable<K, V>> previousSnapshot) {
        return Optional.empty();
    }

    @Override
    public Optional<SMRSnapshot<DiskBackedCorfuTable<K, V>>> generateIntermediarySnapshot(
            VersionedObjectIdentifier version,
            ObjectOpenOption objectOpenOption) {
        return Optional.of(generateSnapshot(version));
    }

    @Override
    public DiskBackedCorfuTable<K, V> newView(@NonNull RocksDbApi<DiskBackedCorfuTable<K, V>> rocksApi) {
        if (!isRoot()) {
            throw new IllegalStateException("Only the root object cen generate new views.");
        }
        return toBuilder().rocksApi(rocksApi).build();
    }

    private boolean isRoot() {
        return rocksApi == rocksDbSnapshotGenerator;
    }

    @Override
    public CorfuOptions.ConsistencyModel getConsistencyModel() {
        return persistenceOptions.getConsistencyModel();
    }
}
