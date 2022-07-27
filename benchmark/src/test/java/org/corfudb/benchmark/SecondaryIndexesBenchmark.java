package org.corfudb.benchmark;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.corfudb.runtime.CorfuOptions;
import org.corfudb.runtime.collections.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class SecondaryIndexesBenchmark extends UniverseHook {

    @Param({"true"})
    boolean diskBacked;

    private static final int payloadSize = 1024 * 10; // 10 KB
    private static final int maxPutCount = 1024;
    private static final int batchSize = 8;

    private static final int minThreadCount = 8;
    private static final int maxThreadCount = 8;

    @State(Scope.Benchmark)
    public static class BenchmarkState extends UniverseBenchmarkState {
        final Map<Schema.Uuid, Schema.StringValueIndex> primeUuids = new HashMap<>();
        final AtomicInteger threadCount = new AtomicInteger(0);
        Table<Schema.Uuid, Schema.StringValueIndex, ?> table;
        CorfuStore store;
        int iterationCount = 0;
    }

    public static void main(String[] args) throws RunnerException {
        for (int threadCount = minThreadCount; threadCount <= maxThreadCount; threadCount = threadCount * 2) {
            Options opt = jmhBuilder()
                    .threads(threadCount)
                    .forks(0)
                    .build();

            Collection<RunResult> results = new Runner(opt).run();
        }
    }

    private Table<Schema.Uuid, Schema.StringValueIndex, ?> openTable(
            BenchmarkState state) throws
            InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        TableOptions.TableOptionsBuilder optionsBuilder = TableOptions.builder();

        if (diskBacked) {
            final String diskBackedDirectory = "/mnt/";
            final Path persistedCacheLocation = Paths.get(diskBackedDirectory,
                    DEFAULT_STREAM_NAME + state.iterationCount++);
            CorfuOptions.PersistenceOptions persistenceOptions = CorfuOptions.PersistenceOptions.newBuilder()
                    .setConsistencyModel(CorfuOptions.ConsistencyModel.READ_COMMITTED)
                    .setDataPath(persistedCacheLocation.toAbsolutePath().toString())
                    .build();
            optionsBuilder.persistenceOptions(persistenceOptions);
        }

        return state.store.openTable(
                DEFAULT_STREAM_NAMESPACE,
                DEFAULT_STREAM_NAME,
                Schema.Uuid.class,
                Schema.StringValueIndex.class,
                null,
                TableOptions.fromProtoSchema(Schema.StringValueIndex.class, optionsBuilder.build())
                );
    }

    @Setup(Level.Iteration)
    public void prepare(BenchmarkState state) throws InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        state.threadCount.set(0);
    }

    @TearDown
    public void tearDown(BenchmarkState state) {
        if (state.table.corfuTable instanceof PersistedCorfuTable) {
            ((PersistedCorfuTable) state.table.corfuTable).publishStats(System.err::println);
        }
        state.corfuClient.shutdown();
        state.wf.getUniverse().shutdown();

    }

    @Setup
    public void setup(BenchmarkState state) throws InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        setupUniverseFramework(state);

        state.store = new CorfuStore(state.corfuCluster.getLocalCorfuClient().getRuntime());
        state.table = openTable(state);

        for (int threadId = 1; threadId <= maxThreadCount; threadId++) {
            long putCountPrime = 1;
            try (TxnContext tx = state.store.txn(DEFAULT_STREAM_NAMESPACE)) {
                for (int batchCount = 0; batchCount < maxPutCount / batchSize; batchCount++) {
                    for (int putCount = 0; putCount < batchSize; putCount++) {
                        final Schema.Uuid key = Schema.Uuid.newBuilder()
                                .setLsb(putCountPrime)
                                .setMsb(threadId)
                                .build();
                        final Schema.Uuid primeKey = Schema.Uuid.newBuilder()
                                .setLsb(-putCountPrime)
                                .setMsb(-threadId)
                                .build();
                        final String payload = RandomStringUtils.random(payloadSize);
                        final Schema.StringValueIndex primaValue = Schema.StringValueIndex.newBuilder()
                                .setValue(payload)
                                .setSecondary(String.valueOf(-threadId))
                                .build();
                        final Schema.StringValueIndex value = Schema.StringValueIndex.newBuilder()
                                .setValue(payload)
                                .setSecondary(String.valueOf(threadId))
                                .build();
                        tx.putRecord(state.table, primeKey, primaValue, null);
                        tx.putRecord(state.table, key, value, null);
                        putCountPrime++;
                    }
                }
                tx.commit();
            }
        }
    }

    @Benchmark
    @Measurement(iterations = 0)
    @Warmup(iterations = 1)
    public void singleThreadedWrite(BenchmarkState state, BenchmarkParams params) {
        long threadId = state.threadCount.incrementAndGet();
        assertThat(threadId)
                .isGreaterThanOrEqualTo(1)
                .isLessThanOrEqualTo(maxThreadCount);

        final Map<Schema.Uuid, Schema.StringValueIndex> uuids = new HashMap<>();
        int keyCount = 1;

        for (int batchCount = 0; batchCount < maxPutCount / batchSize; batchCount++) {
            try (TxnContext tx = state.store.txn(DEFAULT_STREAM_NAMESPACE)) {
                for (int putCount = 0; putCount < batchSize; putCount++) {
                    Schema.Uuid key = Schema.Uuid.newBuilder()
                            .setLsb(keyCount++)
                            .setMsb(threadId)
                            .build();
                    assertThat(key).isNotIn(state.primeUuids);
                    assertThat(key).isNotIn(uuids);
                    final String payload = RandomStringUtils.random(payloadSize);
                    Schema.StringValueIndex value = Schema.StringValueIndex.newBuilder()
                            .setValue(payload)
                            .setSecondary(String.valueOf(threadId))
                            .build();
                    uuids.put(key, value);
                    tx.putRecord(state.table, key, value, null);
                }

                tx.commit();
            }
        }

        assertThat(uuids.size()).isEqualTo(maxPutCount);
        assertThat(uuids.entrySet().stream()
                .filter(entry -> entry.getValue().getSecondary().equals(String.valueOf(threadId)))
                .map(Map.Entry::getKey).collect(Collectors.toSet()).size()).isEqualTo(maxPutCount);

        final StopWatch watch = new StopWatch();

        watch.reset();
        watch.start();

        for (int threadCount = 1; threadCount <= maxThreadCount; threadCount++) {
            try (TxnContext tx = state.store.txn(DEFAULT_STREAM_NAMESPACE)) {
                assertThat(tx.getByIndex(state.table, "secondary", String.valueOf(threadCount)).size()).isEqualTo(maxPutCount);
            }
        }

        watch.stop();
        System.out.println(threadId + " Recent getByIndex " + watch.getTime(TimeUnit.MILLISECONDS));

        watch.reset();
        watch.start();

        for (int threadCount = 1; threadCount <= maxThreadCount; threadCount++) {
            try (TxnContext tx = state.store.txn(DEFAULT_STREAM_NAMESPACE)) {
                assertThat(tx.getByIndex(state.table, "secondary", String.valueOf(-threadCount)).size()).isEqualTo(maxPutCount);
            }
        }

        watch.stop();
        System.out.println(threadId + " Old getByIndex " + watch.getTime(TimeUnit.MILLISECONDS));
    }

}