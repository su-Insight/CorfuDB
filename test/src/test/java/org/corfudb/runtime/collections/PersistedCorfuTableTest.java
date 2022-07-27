package org.corfudb.runtime.collections;

import com.google.common.collect.Streams;
import com.google.common.math.Quantiles;
import com.google.gson.Gson;
import com.google.protobuf.Message;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.api.constraints.UniqueElements;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.corfudb.common.metrics.micrometer.MeterRegistryProvider;
import org.corfudb.protocols.wireprotocol.Token;
import org.corfudb.runtime.CorfuOptions.ConsistencyModel;
import org.corfudb.runtime.CorfuRuntime;
import org.corfudb.runtime.CorfuRuntime.CorfuRuntimeParameters;
import org.corfudb.runtime.CorfuStoreMetadata;
import org.corfudb.runtime.exceptions.TransactionAbortedException;
import org.corfudb.runtime.exceptions.TrimmedException;
import org.corfudb.runtime.exceptions.unrecoverable.UnrecoverableCorfuError;
import org.corfudb.runtime.object.PersistenceOptions;
import org.corfudb.runtime.object.PersistenceOptions.PersistenceOptionsBuilder;
import org.corfudb.runtime.object.RocksDbReadCommittedTx;
import org.corfudb.runtime.view.AbstractViewTest;
import org.corfudb.test.SampleSchema;
import org.corfudb.test.SampleSchema.EventInfo;
import org.corfudb.test.SampleSchema.Uuid;
import org.corfudb.util.serializer.ISerializer;
import org.junit.Test;
import org.junit.jupiter.api.Assertions;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.rocksdb.Env;
import org.rocksdb.OptimisticTransactionDB;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.apache.commons.lang3.function.Failable;
import org.rocksdb.SstFileManager;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.corfudb.common.metrics.micrometer.MeterRegistryProvider.MeterRegistryInitializer.initClientMetrics;

@Slf4j
@RunWith(MockitoJUnitRunner.class)
public class PersistedCorfuTableTest extends AbstractViewTest implements AutoCloseable {

    private static final String defaultTableName = "diskBackedTable";
    private static final String alternateTableName = "diskBackedTable2";
    private static final String diskBackedDirectory = "/tmp/";
    private static final Path persistedCacheLocation = Paths.get(diskBackedDirectory, alternateTableName);
    private static final Options defaultOptions = new Options().setCreateIfMissing(true);
    private static final ISerializer defaultSerializer = new PojoSerializer(String.class);
    private static final int SAMPLE_SIZE = 100;
    private static final int NUM_OF_TRIES = 1;
    private static final int STRING_MIN = 5;
    private static final int STRING_MAX = 10;

    private static final String nonExistingKey = "nonExistingKey";
    private static final String defaultNewMapEntry = "newEntry";
    private static final boolean ENABLE_READ_YOUR_WRITES = true;

    public PersistedCorfuTableTest() {
        AbstractViewTest.initEventGroup();
        resetTests();
    }

    @Override
    public void close() {
        super.cleanupBuffers();
        AbstractViewTest.cleanEventGroup();
    }

    /**
     * Single type POJO serializer.
     */
    public static class PojoSerializer implements ISerializer {
        private final Gson gson = new Gson();
        private final Class<?> clazz;
        private final int SERIALIZER_OFFSET = 29;  // Random number.

        PojoSerializer(Class<?> clazz) {
            this.clazz = clazz;
        }

        @Override
        public byte getType() {
            return SERIALIZER_OFFSET;
        }

        @Override
        public Object deserialize(ByteBuf b, CorfuRuntime rt) {
            return gson.fromJson(new String(ByteBufUtil.getBytes(b)), clazz);
        }

        @Override
        public void serialize(Object o, ByteBuf b) {
            b.writeBytes(gson.toJson(o).getBytes());
        }
    }

    /**
     * Sample POJO class.
     */
    @Data
    @Builder
    public static class Pojo {
        public final String payload;
    }

    private PersistedCorfuTable<String, String> setupTable(
            String streamName, Index.Registry<String, String> registry,
            Options options, ISerializer serializer) {

        PersistenceOptionsBuilder persistenceOptions = PersistenceOptions.builder()
                .dataPath(Paths.get(diskBackedDirectory, streamName));

        return getDefaultRuntime().getObjectsView().build()
                .setTypeToken(PersistedCorfuTable.<String, String>getTypeToken())
                .setArguments(persistenceOptions.build(), options, serializer, registry)
                .setStreamName(streamName)
                .setSerializer(serializer)
                .open();
    }

    private PersistedCorfuTable<String, String> setupTable(Index.Registry<String, String> registry) {
        return setupTable(defaultTableName, registry, defaultOptions, defaultSerializer);
    }

    private <V> PersistedCorfuTable<String, V> setupTable(
            String streamName, boolean readYourWrites,
            Options options, ISerializer serializer) {

        PersistenceOptionsBuilder persistenceOptions = PersistenceOptions.builder()
                .dataPath(Paths.get(diskBackedDirectory, streamName));
        if (!readYourWrites) {
            persistenceOptions.consistencyModel(ConsistencyModel.READ_COMMITTED);
        }

        return getDefaultRuntime().getObjectsView().build()
                .setTypeToken(PersistedCorfuTable.<String, V>getTypeToken())
                .setArguments(persistenceOptions.build(), options, serializer)
                .setStreamName(streamName)
                .setSerializer(serializer)
                .open();
    }

    private PersistedCorfuTable<String, String> setupTable(String streamName, boolean readYourWrites) {
        return setupTable(streamName, readYourWrites, defaultOptions, defaultSerializer);
    }

    private PersistedCorfuTable<String, String> setupTable(boolean readYourWrites) {
        return setupTable(defaultTableName, readYourWrites);
    }

    private PersistedCorfuTable<String, String> setupTable() {
        return setupTable(defaultTableName, ENABLE_READ_YOUR_WRITES);
    }

    private PersistedCorfuTable<String, String> setupTable(String tableName) {
        return setupTable(tableName, ENABLE_READ_YOUR_WRITES);
    }

    /**
     * Executed the specified function in a transaction.
     * @param functor function which will be executed within a transaction
     * @return the address of the commit
     */
    private long executeTx(Runnable functor) {
        long commitAddress;
        getDefaultRuntime().getObjectsView().TXBegin();
        try {
            functor.run();
        } finally {
            commitAddress = getDefaultRuntime().getObjectsView().TXEnd();
        }

        return commitAddress;
    }

    @Override
    public void resetTests() {
        RocksDB.loadLibrary();
        super.resetTests();
    }

    /**
     * Ensure that file-system quota is obeyed.
     *
     * @throws RocksDBException should not be thrown
     */
    @Property(tries = NUM_OF_TRIES)
    void fileSystemLimit() throws Exception {
        resetTests();

        SstFileManager sstFileManager = new SstFileManager(Env.getDefault());
        sstFileManager.setMaxAllowedSpaceUsage(FileUtils.ONE_KB);
        sstFileManager.setCompactionBufferSize(FileUtils.ONE_KB);

        final Options options =
                new Options().setCreateIfMissing(true)
                        .setSstFileManager(sstFileManager)
                        // The size is checked either during flush or compaction.
                        .setWriteBufferSize(FileUtils.ONE_KB);

        try (final PersistedCorfuTable<String, String> table =
                     setupTable(defaultTableName, ENABLE_READ_YOUR_WRITES, options, defaultSerializer)) {

            final long ITERATION_COUNT = 100000;
            final int ENTITY_CHAR_SIZE = 1000;

            assertThatThrownBy(() ->
                    LongStream.rangeClosed(1, ITERATION_COUNT).forEach(idx -> {
                        String key = RandomStringUtils.random(ENTITY_CHAR_SIZE, true, true);
                        String value = RandomStringUtils.random(ENTITY_CHAR_SIZE, true, true);
                        table.insert(key, value);
                        String persistedValue = table.get(key);
                        Assertions.assertEquals(value, persistedValue);
                    })).isInstanceOf(UnrecoverableCorfuError.class)
                    .hasCauseInstanceOf(RocksDBException.class);
        }
    }


    /**
     * Ensure disk-backed table serialization and deserialization works as expected.
     */
    @Property(tries = NUM_OF_TRIES)
    void customSerializer() {
        resetTests();

        try (final PersistedCorfuTable<String, Pojo> table = setupTable(
                defaultTableName, ENABLE_READ_YOUR_WRITES, defaultOptions, new PojoSerializer(Pojo.class))) {

            final long ITERATION_COUNT = 100;
            final int ENTITY_CHAR_SIZE = 100;

            LongStream.rangeClosed(1, ITERATION_COUNT).forEach(idx -> {
                String key = RandomStringUtils.random(ENTITY_CHAR_SIZE, true, true);
                Pojo value = Pojo.builder()
                        .payload(RandomStringUtils.random(ENTITY_CHAR_SIZE, true, true))
                        .build();
                table.insert(key, value);
                Pojo persistedValue = table.get(key);
                Assertions.assertEquals(value, persistedValue);
            });
        }
    }

    /**
     * Transactional property based test that does puts followed by scan and filter.
     */
    @Property(tries = NUM_OF_TRIES)
    void txPutScanAndFilter(@ForAll @Size(SAMPLE_SIZE) Set<String> intended) {
        resetTests();
        try (final PersistedCorfuTable<String, String> table = setupTable()) {
            executeTx(() -> intended.forEach(value -> table.insert(value, value)));
            Assertions.assertEquals(intended.size(), table.size());

            executeTx(() -> {
                final Set<String> persisted = table.entryStream().map(Map.Entry::getValue).collect(Collectors.toSet());
                Assertions.assertEquals(intended, persisted);
                Assertions.assertEquals(table.size(), persisted.size());
            });
        }
    }


    /**
     * Non-transactional property based test that does puts followed by scan and filter.
     */
    @Property(tries = NUM_OF_TRIES)
    void nonTxPutScanAndFilter(@ForAll @Size(SAMPLE_SIZE) Set<String> intended) {
        resetTests();
        try (final PersistedCorfuTable<String, String> table = setupTable()) {
            intended.forEach(value -> table.insert(value, value));
            Assertions.assertEquals(intended.size(), table.size());

            final Set<String> persisted = table.entryStream().map(Map.Entry::getValue).collect(Collectors.toSet());
            Assertions.assertEquals(intended, persisted);
            Assertions.assertEquals(table.size(), persisted.size());
        }
    }

    @Property(tries = NUM_OF_TRIES)
    void nonTxInsertGetRemove(@ForAll @Size(SAMPLE_SIZE) Set<String> intended) {
        resetTests();
        try (final PersistedCorfuTable<String, String> table = setupTable()) {
            intended.forEach(value -> table.insert(value, value));
            Assertions.assertEquals(table.size(), intended.size());
            intended.forEach(value -> Assertions.assertEquals(table.get(value), value));
            intended.forEach(table::delete);

            final Set<String> persisted = table.entryStream().map(Map.Entry::getValue).collect(Collectors.toSet());
            Assertions.assertTrue(persisted.isEmpty());
        }
    }

    @Property(tries = NUM_OF_TRIES)
    void txInsertGetRemove(@ForAll @Size(SAMPLE_SIZE) Set<String> intended) {
        resetTests();
        try (final PersistedCorfuTable<String, String> table = setupTable()) {
            executeTx(() -> intended.forEach(value -> table.insert(value, value)));
            Assertions.assertEquals(table.size(), intended.size());
            executeTx(() -> {
                intended.forEach(value -> Assertions.assertEquals(table.get(value), value));
                intended.forEach(table::delete);
            });

            executeTx(() -> {
                final Set<String> persisted = table.entryStream().map(Map.Entry::getValue).collect(Collectors.toSet());
                Assertions.assertTrue(persisted.isEmpty());
            });
        }
    }

    /**
     * Verify basic non-transactional get and insert operations.
     */
    @Property(tries = NUM_OF_TRIES)
    void nonTxGetAndInsert(@ForAll @Size(SAMPLE_SIZE) Set<String> intended) {
        resetTests();
        try (final PersistedCorfuTable<String, String> table = setupTable()) {
            intended.forEach(value -> table.insert(value, value));
            intended.forEach(k -> Assertions.assertEquals(k, table.get(k)));
        }
    }

    /**
     * Verify commit address of transactions for disk-backed tables.
     */
    @Property(tries = NUM_OF_TRIES)
    void verifyCommitAddressMultiTable(@ForAll @Size(SAMPLE_SIZE) Set<String> intended) {
        resetTests();
        try (final PersistedCorfuTable<String, String> table1 = setupTable();
             final PersistedCorfuTable<String, String> table2 = setupTable(alternateTableName)) {
            table1.insert(defaultNewMapEntry, defaultNewMapEntry);

            assertThat(executeTx(() -> {
                table1.get(nonExistingKey);
                table2.get(nonExistingKey);
            })).isZero();

            intended.forEach(value -> table2.insert(value, value));
            assertThat(executeTx(() -> {
                table1.get(nonExistingKey);
                table2.get(nonExistingKey);
            })).isEqualTo(intended.size());
        }
    }

    /**
     * Verify commit address of interleaving transactions on disk-backed tables.
     */
    // TODO(ZacH):
    @Property(tries = NUM_OF_TRIES)
    void verifyCommitAddressInterleavingTxn(@ForAll @Size(SAMPLE_SIZE) Set<String> intended) throws Exception {
        resetTests();
        try (final PersistedCorfuTable<String, String> table = setupTable()) {
            CountDownLatch latch1 = new CountDownLatch(1);
            CountDownLatch latch2 = new CountDownLatch(1);

            Thread t1 = new Thread(() -> {
                table.insert(defaultNewMapEntry, defaultNewMapEntry);
                assertThat(executeTx(() -> table.get(nonExistingKey))).isEqualTo(0L);
                assertThat(executeTx(() -> {
                    try {
                        table.get(nonExistingKey);
                        latch2.countDown();
                        latch1.await();
                        table.get(nonExistingKey);
                    } catch (InterruptedException ignored) {
                        // Ignored
                    }
                })).isEqualTo(intended.size());
            });

            Thread t2 = new Thread(() -> {
                try {
                    latch2.await();
                    intended.forEach(value -> table.insert(value, value));
                    assertThat(executeTx(() -> table.get(nonExistingKey))).isEqualTo(intended.size());
                    latch1.countDown();
                } catch (InterruptedException ex) {
                    // Ignored
                }
            });

            t1.start();
            t2.start();
            t1.join();
            t2.join();
            intended.forEach(value -> assertThat(table.get(value)).isEqualTo(value));
        }
    }


    /**
     * Test the snapshot isolation property of disk-backed Corfu tables.
     */
    @Property(tries = NUM_OF_TRIES)
    void snapshotIsolation(@ForAll @Size(SAMPLE_SIZE) Set<String> intended) throws Exception {
        resetTests();
        try (final PersistedCorfuTable<String, String> table = setupTable()) {
            AtomicBoolean failure = new AtomicBoolean(false);
            CountDownLatch latch1 = new CountDownLatch(1);
            CountDownLatch latch2 = new CountDownLatch(1);
            final int numUpdates = 5;

            Thread t1 = new Thread(() -> {
                // Initial updates [0, numUpdates).
                for (int i = 0; i < numUpdates; i++) {
                    final int ind = i;
                    executeTx(() -> table.insert(defaultNewMapEntry + ind, defaultNewMapEntry + ind));
                }

                executeTx(() -> {
                    try {
                        log.info("Checking data before thread two performs updates.");
                        for (int i = 0; i < 2*numUpdates; i++) {
                            if (i < numUpdates) {
                                assertThat(table.get(defaultNewMapEntry + i)).isEqualTo(defaultNewMapEntry + i);
                                log.info("key={} has value={}.", defaultNewMapEntry + i, defaultNewMapEntry + i);
                            } else {
                                assertThat(table.get(defaultNewMapEntry + i)).isNull();
                                log.info("key={} has value=null.", defaultNewMapEntry + i);
                            }
                        }

                        log.info("Waiting for thread two.");
                        latch2.countDown();
                        latch1.await();
                        log.info("Waited for thread two. Checking data again.");

                        // Validate that the same snapshot is observed after thread two finishes.
                        for (int i = 0; i < 2*numUpdates; i++) {
                            if (i < numUpdates) {
                                assertThat(table.get(defaultNewMapEntry + i)).isEqualTo(defaultNewMapEntry + i);
                                log.info("key={} has value={}.", defaultNewMapEntry + i, defaultNewMapEntry + i);
                            } else {
                                assertThat(table.get(defaultNewMapEntry + i)).isNull();
                                log.info("key={} has value=null.", defaultNewMapEntry + i);
                            }
                        }

                        log.info("Thread one DONE.");
                    } catch (Exception ex) {
                        failure.set(true);
                    }
                });
            });

            Thread t2 = new Thread(() -> {
                try {
                    // Wait until thread one performs initial writes.
                    log.info("Waiting for thread one.");
                    latch2.await();
                    log.info("Waited for thread one. Populating new data.");

                    // Populate additional entries [numUpdates, 2*numUpdates).
                    for (int i = numUpdates; i < 2*numUpdates; i++) {
                        final int ind = i;
                        executeTx(() -> table.insert(defaultNewMapEntry + ind, defaultNewMapEntry + ind));
                    }

                    assertThat(table.get(defaultNewMapEntry + numUpdates)).isEqualTo(defaultNewMapEntry + numUpdates);
                    log.info("Thread two DONE.");
                    latch1.countDown();
                } catch (Exception ex) {
                    failure.set(true);
                }
            });

            t1.start();
            t2.start();
            t1.join();
            t2.join();
            assertThat(failure.get()).isFalse();
        }
    }

    /**
     * Test the read-your-own-writes property of disk-backed Corfu tables.
     */
    @Property(tries = NUM_OF_TRIES)
    void readYourOwnWrites() {
        resetTests();
        try (final PersistedCorfuTable<String, String> table = setupTable()) {
            executeTx(() -> {
                assertThat(table.get(defaultNewMapEntry)).isNull();
                table.insert(defaultNewMapEntry, defaultNewMapEntry);
                assertThat(table.get(defaultNewMapEntry)).isEqualTo(defaultNewMapEntry);
            });
        }
    }

    @Property(tries = NUM_OF_TRIES)
    void invalidView() {
        PersistenceOptionsBuilder persistenceOptions = PersistenceOptions.builder()
                .dataPath(Paths.get(diskBackedDirectory, defaultTableName));

        OptimisticTransactionDB rocksDb = Mockito.mock(OptimisticTransactionDB.class);

        try (DiskBackedCorfuTable<String, String> table = new DiskBackedCorfuTable<>(
                persistenceOptions.build(), defaultOptions, defaultSerializer)) {
            DiskBackedCorfuTable<String, String> newView = table.newView(new RocksDbReadCommittedTx<>(rocksDb));
            assertThat(newView).isNotNull();
            assertThatThrownBy(() -> newView.newView(new RocksDbReadCommittedTx<>(rocksDb)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
    @Property(tries = NUM_OF_TRIES)
    void snapshotExpiredCrud(@ForAll @Size(SAMPLE_SIZE) Set<String> intended) {
        resetTests(CorfuRuntimeParameters.builder().mvoCacheExpiry(Duration.ofNanos(0)).build());
        try (final PersistedCorfuTable<String, String> table = setupTable()) {
            executeTx(() -> {
                intended.forEach(entry -> table.insert(entry, entry));
                intended.forEach(entry -> assertThat(table.get(entry)).isEqualTo(entry));
            });

            executeTx(() -> {
                intended.forEach(entry -> assertThat(table.get(entry)).isEqualTo(entry));

                Thread thread = new Thread(() -> {
                    intended.forEach(entry -> table.insert(entry, StringUtils.reverse(entry)));
                    intended.forEach(entry -> assertThat(table.get(entry)).isEqualTo(StringUtils.reverse(entry)));
                });

                thread.start();
                Failable.run(thread::join);

                table.getCorfuSMRProxy().getUnderlyingMVO()
                        .getMvoCache().getObjectCache().cleanUp();

                assertThatThrownBy(() -> table.get(intended.stream().findFirst().get()))
                        .isInstanceOf(TransactionAbortedException.class);
            });

        }
    }

        @Property(tries = NUM_OF_TRIES)
        void snapshotExpiredIterator(@ForAll @Size(SAMPLE_SIZE) Set<String> intended) {
            resetTests(CorfuRuntimeParameters.builder().mvoCacheExpiry(Duration.ofNanos(0)).build());
            try (final PersistedCorfuTable<String, String> table = setupTable()) {

                executeTx(() -> {
                    intended.forEach(entry -> table.insert(entry, entry));
                    intended.forEach(entry -> assertThat(table.get(entry)).isEqualTo(entry));
                });

                executeTx(() -> {
                    assertThat(table.entryStream().count()).isEqualTo(intended.size());

                    Stream<Map.Entry<String, String>> stream = table.entryStream();
                    Iterator<Map.Entry<String, String>> iterator = stream.iterator();
                    assertThat(iterator.next()).isNotNull();
                    assertThat(iterator.next()).isNotNull();

                    Thread thread = new Thread(() -> {
                        intended.forEach(entry -> table.insert(entry, StringUtils.reverse(entry)));
                        intended.forEach(entry -> assertThat(table.get(entry)).isEqualTo(StringUtils.reverse(entry)));
                    });

                    thread.start();
                    Failable.run(thread::join);

                    table.getCorfuSMRProxy().getUnderlyingMVO()
                            .getMvoCache().getObjectCache().cleanUp();
                    assertThatThrownBy(iterator::next)
                            .isInstanceOf(TrimmedException.class);
                });
        }
    }

    @Property(tries = NUM_OF_TRIES)
    void noReadYourOwnWrites(@ForAll @Size(SAMPLE_SIZE) Set<String> intended) throws Exception {
        resetTests();
        try (final PersistedCorfuTable<String, String> table = setupTable(!ENABLE_READ_YOUR_WRITES)) {
            executeTx(() -> {
                assertThat(table.get(defaultNewMapEntry)).isNull();
                table.insert(defaultNewMapEntry, defaultNewMapEntry);
                assertThat(table.get(defaultNewMapEntry)).isNull();
            });
        }
    }

    /**
     * Verify RocksDB persisted cache is cleaned up
     */
    @Property(tries = NUM_OF_TRIES)
    void verifyPersistedCacheCleanUp() {
        resetTests();
        try (final PersistedCorfuTable<String, String> table1 = setupTable(alternateTableName, ENABLE_READ_YOUR_WRITES)) {
            table1.insert(defaultNewMapEntry, defaultNewMapEntry);
            assertThat(persistedCacheLocation).exists();
        }

        assertThat(persistedCacheLocation).doesNotExist();
    }

    @Property(tries = NUM_OF_TRIES)
    void testClear(@ForAll @UniqueElements @Size(SAMPLE_SIZE)
                   Set<@AlphaChars @StringLength(min = 1) String> intended) {
        resetTests();
        try (final PersistedCorfuTable<String, String> table = setupTable(new StringIndexer())) {
            executeTx(() -> intended.forEach(entry -> table.insert(entry, entry)));
            executeTx(() -> assertThat(table.entryStream().count()).isEqualTo(intended.size()));
            assertThat(table.entryStream().count()).isEqualTo(intended.size());

            Map<Character, Set<String>> groups = intended.stream()
                    .collect(Collectors.groupingBy(s -> s.charAt(0), Collectors.toSet()));

            groups.forEach(((character, strings) -> assertThat(StreamSupport.stream(
                            table.getByIndex(StringIndexer.BY_FIRST_LETTER, character).spliterator(), false)
                    .map(Map.Entry::getValue)
                    .collect(Collectors.toSet()))
                    .isEqualTo(strings)));

            executeTx(() -> {
                table.clear();

                // Ensure correctness from read-your-writes perspective.
                assertThat(table.entryStream().count()).isEqualTo(0);
                intended.forEach(key -> assertThat(table.get(key)).isNull());

                groups.forEach(((character, strings) -> assertThat(StreamSupport.stream(
                                table.getByIndex(StringIndexer.BY_FIRST_LETTER, character).spliterator(), false)
                        .map(Map.Entry::getValue)
                        .collect(Collectors.toSet()))
                        .isEmpty()));
            });

            // Ensure correctness from global perspective.
            executeTx(() -> assertThat(table.entryStream().count()).isEqualTo(0));
            assertThat(table.entryStream().count()).isEqualTo(0);

            executeTx(() -> groups.forEach(((character, strings) -> assertThat(StreamSupport.stream(
                                table.getByIndex(StringIndexer.BY_FIRST_LETTER, character).spliterator(), false)
                        .map(Map.Entry::getValue)
                        .collect(Collectors.toSet()))
                        .isEmpty())));
            groups.forEach(((character, strings) -> assertThat(StreamSupport.stream(
                            table.getByIndex(StringIndexer.BY_FIRST_LETTER, character).spliterator(), false)
                    .map(Map.Entry::getValue)
                    .collect(Collectors.toSet()))
                    .isEmpty()));
        }
    }

    @Property(tries = NUM_OF_TRIES)
    void testUnmapSecondaryIndexesAndAbort(@ForAll @UniqueElements @Size(SAMPLE_SIZE)
                                           Set<@AlphaChars @StringLength(min = 1) String> intended) {
        resetTests();
        try (final PersistedCorfuTable<String, String> table = setupTable(new StringIndexer())) {
            // StringIndexer does not work with empty strings (StringIndexOutOfBoundsException)
            executeTx(() -> intended.forEach(value -> table.insert(StringUtils.reverse(value), value)));

            final Set<String> persisted = table.entryStream().map(Map.Entry::getValue).collect(Collectors.toSet());
            assertThat(intended).isEqualTo(persisted);

            Map<Character, Set<String>> groups = persisted.stream()
                    .collect(Collectors.groupingBy(s -> s.charAt(0), Collectors.toSet()));

            executeTx(() -> {
                // Transactional getByIndex.
                groups.forEach(((character, strings) -> assertThat(StreamSupport.stream(
                                table.getByIndex(StringIndexer.BY_FIRST_LETTER, character).spliterator(), false)
                        .map(Map.Entry::getValue)
                        .collect(Collectors.toSet()))
                        .isEqualTo(strings)));

                intended.forEach(value -> table.delete(StringUtils.reverse(value)));

                groups.forEach(((character, strings) -> assertThat(StreamSupport.stream(
                                table.getByIndex(StringIndexer.BY_FIRST_LETTER, character).spliterator(), false)
                        .map(Map.Entry::getValue)
                        .collect(Collectors.toSet()))
                        .isEmpty()));

                getDefaultRuntime().getObjectsView().TXAbort();
            });

            groups.forEach(((character, strings) -> assertThat(StreamSupport.stream(
                            table.getByIndex(StringIndexer.BY_FIRST_LETTER, character).spliterator(), false)
                    .map(Map.Entry::getValue)
                    .collect(Collectors.toSet()))
                    .isEqualTo(strings)));
        }
    }

    @Property(tries = NUM_OF_TRIES)
    void testSecondaryIndexes(@ForAll @UniqueElements @Size(SAMPLE_SIZE)
                              Set<@AlphaChars @StringLength(min = 1) String> intended) {
        resetTests();
        try (final PersistedCorfuTable<String, String> table = setupTable(new StringIndexer())) {
            executeTx(() -> intended.forEach(value -> table.insert(value, value)));

            {
                final Set<String> persisted = table.entryStream().map(Map.Entry::getValue).collect(Collectors.toSet());
                assertThat(intended).isEqualTo(persisted);
                Map<Character, Set<String>> groups = persisted.stream()
                        .collect(Collectors.groupingBy(s -> s.charAt(0), Collectors.toSet()));

                // Non-transactional getByIndex.
                groups.forEach(((character, strings) -> assertThat(StreamSupport.stream(
                                table.getByIndex(StringIndexer.BY_FIRST_LETTER, character).spliterator(), false)
                        .map(Map.Entry::getValue)
                        .collect(Collectors.toSet()))
                        .isEqualTo(strings)));
            }

            executeTx(() -> {
                final Set<String> persisted = table.entryStream().map(Map.Entry::getValue).collect(Collectors.toSet());

                {
                    assertThat(intended).isEqualTo(persisted);
                    Map<Character, Set<String>> groups = persisted.stream()
                            .collect(Collectors.groupingBy(s -> s.charAt(0), Collectors.toSet()));

                    // Transactional getByIndex.
                    groups.forEach(((character, strings) -> assertThat(StreamSupport.stream(
                                    table.getByIndex(StringIndexer.BY_FIRST_LETTER, character).spliterator(), false)
                            .map(Map.Entry::getValue)
                            .collect(Collectors.toSet()))
                            .isEqualTo(strings)));
                }

                intended.forEach(value -> table.insert(value, StringUtils.reverse(value)));
                intended.forEach(value -> table.insert(StringUtils.reverse(value), StringUtils.reverse(value)));

                {
                    final Set<String> newPersisted = table.entryStream().map(Map.Entry::getValue).collect(Collectors.toSet());
                    Map<Character, Set<String>> groups = newPersisted.stream()
                            .collect(Collectors.groupingBy(s -> s.charAt(0), Collectors.toSet()));

                    groups.forEach(((character, strings) -> assertThat(StreamSupport.stream(
                                    table.getByIndex(StringIndexer.BY_FIRST_LETTER, character).spliterator(), false)
                            .map(Map.Entry::getValue)
                            .collect(Collectors.toSet()))
                            .isEqualTo(strings)));
                }
            });
        }
    }

    /**
     * A custom generator for {@link Uuid}.
     */
    @Provide
    Arbitrary<Uuid> uuid() {
        return Arbitraries.integers().map(
                idx -> Uuid.newBuilder().setMsb(idx).setLsb(idx).build());
    }

    /**
     * A custom generator for {@link EventInfo}.
     */
    @Provide
    Arbitrary<EventInfo> eventInfo() {
        return Arbitraries.integers().map(idx ->
                EventInfo.newBuilder()
                        .setId(idx)
                        .setName("event_" + idx)
                        .setEventTime(idx)
                        .build());
    }

    /**
     * A custom generator for a set of {@link EventInfo}.
     */
    @Provide
    Arbitrary<Set<Uuid>> uuidSet() {
        return uuid().set();
    }

    @Provide
    Arbitrary<Set<EventInfo>> eventInfoSet() {
        return eventInfo().set();
    }

    /**
     * Check {@link PersistedCorfuTable} integration with {@link CorfuStore}.
     */
    @Property(tries = NUM_OF_TRIES)
    void dataStoreIntegration(
            @ForAll @StringLength(min = STRING_MIN, max = STRING_MAX) @AlphaChars String namespace,
            @ForAll @StringLength(min = STRING_MIN, max = STRING_MAX) @AlphaChars String tableName,
            @ForAll("uuidSet") @Size(SAMPLE_SIZE + 1) Set<Uuid> ids,
            @ForAll("eventInfoSet") @Size(SAMPLE_SIZE + 1) Set<EventInfo> events)
            throws Exception {
        resetTests();

        final Uuid firstId = ids.stream().findFirst().orElseThrow(IllegalStateException::new);
        final EventInfo firstEvent = events.stream().findAny().orElseThrow(IllegalStateException::new);
        assertThat(ids.remove(firstId)).isTrue();
        assertThat(events.remove(firstEvent)).isTrue();

        assertThat(ids.size()).isEqualTo(SAMPLE_SIZE);
        assertThat(events.size()).isEqualTo(SAMPLE_SIZE);

        // Creating Corfu Store using a connected corfu client.
        CorfuStore corfuStore = new CorfuStore(getDefaultRuntime());

        // Create & Register the table.
        // This is required to initialize the table for the current corfu client.
        final Path persistedCacheLocation = Paths.get(diskBackedDirectory, defaultTableName);
        try (Table<Uuid, EventInfo, SampleSchema.ManagedResources> table =
                corfuStore.openTable(namespace, tableName,
                        Uuid.class, EventInfo.class,
                        SampleSchema.ManagedResources.class,
                        // TableOptions includes option to choose - Memory/Disk based corfu table.
                        TableOptions.fromProtoSchema(EventInfo.class).toBuilder()
                                .persistentDataPath(persistedCacheLocation)
                                .build())) {

            SampleSchema.ManagedResources metadata = SampleSchema.ManagedResources.newBuilder()
                    .setCreateUser("MrProto").build();

            // Simple CRUD using the table instance.
            // These are wrapped as transactional operations.
            table.put(firstId, firstEvent, metadata);

            // Fetch timestamp to perform snapshot queries or transactions at a particular timestamp.
            Token token = getDefaultRuntime().getSequencerView().query().getToken();
            CorfuStoreMetadata.Timestamp timestamp = CorfuStoreMetadata.Timestamp.newBuilder()
                    .setEpoch(token.getEpoch())
                    .setSequence(token.getSequence())
                    .build();

            try (TxnContext tx = corfuStore.txn(namespace)) {
                assertThat(events.size()).isEqualTo(ids.size());

                Streams.zip(ids.stream(), events.stream(), SimpleEntry::new)
                        .forEach(pair -> tx.putRecord(table, pair.getKey(), pair.getValue(), metadata));
                CorfuStoreMetadata.Timestamp t = tx.commit();
            }

            final SimpleEntry<Uuid, EventInfo> sample = Streams
                    .zip(ids.stream(), events.stream(), SimpleEntry::new)
                    .findAny().orElseThrow(() -> new InvalidObjectException("Invalid state."));

            try (TxnContext tx = corfuStore.txn(namespace)) {
                assertThat(tx.getRecord(tableName, sample.getKey()).getPayload())
                        .isEqualTo(sample.getValue());

                final Collection<Message> secondaryIndex = tx
                        .getByIndex(tableName, "event_time", sample.getValue().getEventTime())
                        .stream().map(CorfuStoreEntry::getPayload).collect(Collectors.toList());

                assertThat(secondaryIndex).containsExactly(sample.getValue());

                long medianEventTime = (long) Quantiles.median().compute(events.stream()
                        .map(EventInfo::getEventTime)
                        .collect(Collectors.toList()));

                events.add(firstEvent);
                final Set<EventInfo> filteredEvents = events.stream().filter(
                                event -> event.getEventTime() > medianEventTime)
                        .collect(Collectors.toSet());
                final List<CorfuStoreEntry<Uuid, EventInfo, SampleSchema.ManagedResources>> queryResult =
                        tx.executeQuery(tableName,
                                record -> record.getPayload().getEventTime() > medianEventTime);
                final Set<EventInfo> scannedValues = queryResult.stream()
                        .map(CorfuStoreEntry::getPayload).collect(Collectors.toSet());

                assertThat(filteredEvents.size()).isGreaterThan(0).isLessThan(SAMPLE_SIZE);
                assertThat(scannedValues.size()).isEqualTo(filteredEvents.size());
                assertThat(tx.count(tableName)).isEqualTo(SAMPLE_SIZE + 1);
                tx.commit();
            }

            try (TxnContext tx = corfuStore.txn(namespace, IsolationLevel.snapshot(timestamp))) {
                assertThat(tx.count(tableName)).isEqualTo(1);
                tx.commit();
            }

            assertThat(corfuStore.listTables(namespace))
                    .containsExactly(CorfuStoreMetadata.TableName.newBuilder()
                            .setNamespace(namespace)
                            .setTableName(tableName)
                            .build());
        }
    }

    @Captor
    private ArgumentCaptor<String> logCaptor;

    @Test
    public void testExternalProvider() throws InterruptedException, IOException {
        final Logger logger = Mockito.mock(Logger.class);
        final List<String> logMessages = new ArrayList<>();
        final CountDownLatch countDownLatch = new CountDownLatch(10);
        final String tableFolderName = "metered-table";

        Mockito.doAnswer(invocation -> {
            synchronized (this) {
                String logMessage = invocation.getArgument(0, String.class);
                if (logMessage.startsWith(tableFolderName)) {
                    countDownLatch.countDown();
                }
                logMessages.add(logMessage);
                return null;
            }
        }).when(logger).debug(logCaptor.capture());

        final Duration loggingInterval = Duration.ofMillis(100);
        initClientMetrics(logger, loggingInterval, PersistedCorfuTableTest.class.toString());

        PersistedCorfuTable<String, String> table = setupTable(tableFolderName);
        countDownLatch.await();
        synchronized (this) {
            assertThat(logMessages.stream().filter(log -> log.contains("rocksdb"))
                    .filter(log -> log.startsWith(tableFolderName))
                    .findAny()).isPresent();
        }

        try (MockedStatic<MeterRegistryProvider> myClassMock =
                    Mockito.mockStatic(MeterRegistryProvider.class)) {
            table.close();
            myClassMock.verify(() -> MeterRegistryProvider.unregisterExternalSupplier(any()), times(1));
        }
    }
}