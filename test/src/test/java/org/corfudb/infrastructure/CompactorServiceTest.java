package org.corfudb.infrastructure;

import com.google.protobuf.Message;
import lombok.extern.slf4j.Slf4j;
import org.corfudb.runtime.CorfuCompactorManagement;
import org.corfudb.runtime.CorfuCompactorManagement.CheckpointingStatus;
import org.corfudb.runtime.CorfuCompactorManagement.CheckpointingStatus.StatusType;
import org.corfudb.runtime.CorfuRuntime;
import org.corfudb.runtime.CorfuStoreMetadata;
import org.corfudb.runtime.collections.CorfuStore;
import org.corfudb.runtime.collections.Table;
import org.corfudb.runtime.collections.TableOptions;
import org.corfudb.runtime.collections.TxnContext;
import org.corfudb.runtime.proto.RpcCommon;
import org.corfudb.runtime.view.AbstractViewTest;
import org.corfudb.runtime.view.Layout;
import org.corfudb.util.concurrent.SingletonResource;
import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.corfudb.runtime.view.TableRegistry.CORFU_SYSTEM_NAMESPACE;

@Slf4j
public class CompactorServiceTest extends AbstractViewTest {

    ServerContext sc0;
    ServerContext sc1;
    ServerContext sc2;

    private static final String COMPACTION_MANAGER_TABLE_NAME = "CompactionManager";
    private static final String CHECKPOINT_STATUS_TABLE_NAME = "CheckpointStatusTable";
    public static final String CHECKPOINT = "checkpoint";

    private static final int LIVENESS_TIMEOUT = 5000;
    private static final int COMPACTOR_SERVICE_INTERVAL = 10;

    private static final CorfuCompactorManagement.StringKey COMPACTION_MANAGER_KEY =
            CorfuCompactorManagement.StringKey.newBuilder().setKey("CompactionManagerKey").build();
    private static final CorfuCompactorManagement.StringKey CHECKPOINT_KEY = CorfuCompactorManagement.StringKey.newBuilder().setKey("minCheckpointToken").build();

    private CorfuRuntime runtime0 = null;
    private CorfuRuntime runtime1 = null;
    private CorfuRuntime runtime2 = null;
    private CorfuRuntime cpRuntime0 = null;
    private CorfuRuntime cpRuntime1 = null;
    private CorfuRuntime cpRuntime2 = null;

    private Layout layout = null;
    private CorfuStore corfuStore = null;

    /**
     * Generates and bootstraps a 3 node cluster in disk mode.
     * Shuts down the management servers of the 3 nodes.
     *
     * @return The generated layout.
     */
    private Layout setup3NodeCluster() {
        sc0 = new ServerContextBuilder()
                .setSingle(false)
                .setServerRouter(new TestServerRouter(SERVERS.PORT_0))
                .setPort(SERVERS.PORT_0)
                .setMemory(false)
                .setCacheSizeHeapRatio("0.0")
                .setLogPath(com.google.common.io.Files.createTempDir().getAbsolutePath())
                .build();
        sc1 = new ServerContextBuilder()
                .setSingle(false)
                .setServerRouter(new TestServerRouter(SERVERS.PORT_1))
                .setPort(SERVERS.PORT_1)
                .setMemory(false)
                .setCacheSizeHeapRatio("0.0")
                .setLogPath(com.google.common.io.Files.createTempDir().getAbsolutePath())
                .build();
        sc2 = new ServerContextBuilder()
                .setSingle(false)
                .setServerRouter(new TestServerRouter(SERVERS.PORT_2))
                .setPort(SERVERS.PORT_2)
                .setMemory(false)
                .setCacheSizeHeapRatio("0.0")
                .setLogPath(com.google.common.io.Files.createTempDir().getAbsolutePath())
                .build();

        addServer(SERVERS.PORT_0, sc0);
        addServer(SERVERS.PORT_1, sc1);
        addServer(SERVERS.PORT_2, sc2);
        Layout l = new TestLayoutBuilder()
                .setEpoch(0L)
                .addLayoutServer(SERVERS.PORT_0)
                .addLayoutServer(SERVERS.PORT_1)
                .addLayoutServer(SERVERS.PORT_2)
                .addSequencer(SERVERS.PORT_0)
                .addSequencer(SERVERS.PORT_1)
                .addSequencer(SERVERS.PORT_2)
                .buildSegment()
                .setReplicationMode(Layout.ReplicationMode.CHAIN_REPLICATION)
                .buildStripe()
                .addLogUnit(SERVERS.PORT_0)
                .addLogUnit(SERVERS.PORT_1)
                .addLogUnit(SERVERS.PORT_2)
                .addToSegment()
                .addToLayout()
                .build();

        bootstrapAllServers(l);

        // Shutdown management servers
//            getManagementServer(SERVERS.PORT_0).shutdown();
//            getManagementServer(SERVERS.PORT_1).shutdown();
//            getManagementServer(SERVERS.PORT_2).shutdown();

        return l;
    }

    @Before
    public void testSetup() {
        layout = setup3NodeCluster();
        runtime0 = getRuntime(layout).connect();
        runtime1 = getRuntime(layout).connect();
        runtime2 = getRuntime(layout).connect();
        cpRuntime0 = getRuntime(layout).connect();
        cpRuntime1 = getRuntime(layout).connect();
        cpRuntime2 = getRuntime(layout).connect();

        corfuStore = new CorfuStore(runtime0);

        System.out.println("testSetup completed");
    }

    private Table<CorfuCompactorManagement.StringKey, CorfuCompactorManagement.CheckpointingStatus, Message> openCompactionManagerTable() {
        try {
            return corfuStore.openTable(CORFU_SYSTEM_NAMESPACE,
                    COMPACTION_MANAGER_TABLE_NAME,
                    CorfuCompactorManagement.StringKey.class,
                    CorfuCompactorManagement.CheckpointingStatus.class,
                    null,
                    TableOptions.fromProtoSchema(CorfuCompactorManagement.CheckpointingStatus.class));
        } catch (Exception e) {
            log.error("Exception while opening tables ", e);
            return null;
        }
    }

    private Table<CorfuStoreMetadata.TableName, CorfuCompactorManagement.CheckpointingStatus, Message> openCheckpointStatusTable() {
        try {
            return corfuStore.openTable(CORFU_SYSTEM_NAMESPACE,
                    CHECKPOINT_STATUS_TABLE_NAME,
                    CorfuStoreMetadata.TableName.class,
                    CorfuCompactorManagement.CheckpointingStatus.class,
                    null,
                    TableOptions.fromProtoSchema(CorfuCompactorManagement.CheckpointingStatus.class));
        } catch (Exception e) {
            log.error("Exception while opening tables ", e);
            return null;
        }
    }

    private Table<CorfuCompactorManagement.StringKey, RpcCommon.TokenMsg, Message> openCheckpointTable() {
        try {
            return corfuStore.openTable(CORFU_SYSTEM_NAMESPACE,
                    CHECKPOINT,
                    CorfuCompactorManagement.StringKey.class,
                    RpcCommon.TokenMsg.class,
                    null,
                    TableOptions.fromProtoSchema(RpcCommon.TokenMsg.class));
        } catch (Exception e) {
            log.error("Exception while opening tables ", e);
            return null;
        }
    }

    private boolean verifyManagerStatus(StatusType targetStatus) {
        openCompactionManagerTable();
        try (TxnContext txn = corfuStore.txn(CORFU_SYSTEM_NAMESPACE)) {
            CorfuCompactorManagement.CheckpointingStatus managerStatus = (CorfuCompactorManagement.CheckpointingStatus) txn.getRecord(COMPACTION_MANAGER_TABLE_NAME,
                    COMPACTION_MANAGER_KEY).getPayload();
            System.out.println("ManagerStatus: " + managerStatus.getStatus());
            if (managerStatus.getStatus() == targetStatus) {
                System.out.println("verifyManagerStatus: returning true");
                return true;
            }
        }
        return false;
    }

    private boolean verifyCheckpointStatusTable(StatusType targetStatus, int maxFailedTables) {

        Table<CorfuStoreMetadata.TableName, CorfuCompactorManagement.CheckpointingStatus, Message> cpStatusTable = openCheckpointStatusTable();

        int failed = 0;
        try (TxnContext txn = corfuStore.txn(CORFU_SYSTEM_NAMESPACE)) {
            List<CorfuStoreMetadata.TableName> tableNames = new ArrayList<>(txn.keySet(cpStatusTable)
                    .stream().collect(Collectors.toList()));
            for (CorfuStoreMetadata.TableName table : tableNames) {
                CorfuCompactorManagement.CheckpointingStatus cpStatus = (CorfuCompactorManagement.CheckpointingStatus) txn.getRecord(CHECKPOINT_STATUS_TABLE_NAME, table).getPayload();
                System.out.println(table.getTableName() +
                        " : " + cpStatus.getStatus() + " clientId: " + cpStatus.getClientId());
                if (cpStatus.getStatus() != targetStatus) {
                    failed++;
                }
            }
            return failed <= maxFailedTables;
        }
    }

    private boolean verifyCheckpointTable() {

        openCheckpointTable();

        RpcCommon.TokenMsg token;
        try (TxnContext txn = corfuStore.txn(CORFU_SYSTEM_NAMESPACE)) {
            token = (RpcCommon.TokenMsg) txn.getRecord(CHECKPOINT, CHECKPOINT_KEY).getPayload();
            txn.commit();
        }
        log.info("verify Token: {}", token == null ? "null" : token.toString());

        if (token != null) {
            return true;
        }
        return false;
    }

    private boolean pollForFinishCheckpointing() {
        try (TxnContext txn = corfuStore.txn(CORFU_SYSTEM_NAMESPACE)) {
            CheckpointingStatus managerStatus = (CheckpointingStatus) txn.getRecord(COMPACTION_MANAGER_TABLE_NAME,
                    COMPACTION_MANAGER_KEY).getPayload();
            txn.commit();
            if (managerStatus != null && (managerStatus.getStatus() == StatusType.COMPLETED
                    || managerStatus.getStatus() == StatusType.FAILED)) {
                log.info("done pollForFinishCp: {}", managerStatus.getStatus());
                return true;
            }
        }
        return false;
    }

    @Test
    public void singleServerTest() {
        SingletonResource<CorfuRuntime> runtimeSingletonResource1 = SingletonResource.withInitial(() -> runtime0);
        MockCompactionTriggerPolicy mockCompactionTriggerPolicy1 = new MockCompactionTriggerPolicy();

        CompactorService compactorService1 = new CompactorService(sc0, runtimeSingletonResource1,
                new InvokeCheckpointingMock(runtime0, cpRuntime0), mockCompactionTriggerPolicy1);
        compactorService1.start(Duration.ofMillis(COMPACTOR_SERVICE_INTERVAL));
        compactorService1.setLIVENESS_TIMEOUT(LIVENESS_TIMEOUT);
        mockCompactionTriggerPolicy1.setShouldTrigger(true);

        try {
            while (!pollForFinishCheckpointing()) {
                TimeUnit.MILLISECONDS.sleep(COMPACTOR_SERVICE_INTERVAL);
            }
        } catch (InterruptedException e) {
            log.warn("Sleep interrupted, ", e);
        }

        assert(verifyManagerStatus(StatusType.COMPLETED));
        assert(verifyCheckpointStatusTable(StatusType.COMPLETED, 0));
        verifyCheckpointTable();
    }

    @Test
    public void multipleServerTest() {
        SingletonResource<CorfuRuntime> runtimeSingletonResource1 = SingletonResource.withInitial(() -> runtime0);
        MockCompactionTriggerPolicy mockCompactionTriggerPolicy1 = new MockCompactionTriggerPolicy();
        SingletonResource<CorfuRuntime> runtimeSingletonResource2 = SingletonResource.withInitial(() -> runtime1);
        MockCompactionTriggerPolicy mockCompactionTriggerPolicy2 = new MockCompactionTriggerPolicy();

        CompactorService compactorService1 = new CompactorService(sc0, runtimeSingletonResource1,
                new InvokeCheckpointingMock(runtime0, cpRuntime0), mockCompactionTriggerPolicy1);
        compactorService1.start(Duration.ofMillis(COMPACTOR_SERVICE_INTERVAL));
        compactorService1.setLIVENESS_TIMEOUT(LIVENESS_TIMEOUT);
        mockCompactionTriggerPolicy1.setShouldTrigger(true);

        CompactorService compactorService2 = new CompactorService(sc1, runtimeSingletonResource2,
                new InvokeCheckpointingMock(runtime1, cpRuntime1), mockCompactionTriggerPolicy2);
        compactorService2.start(Duration.ofMillis(COMPACTOR_SERVICE_INTERVAL));
        compactorService2.setLIVENESS_TIMEOUT(LIVENESS_TIMEOUT);
        mockCompactionTriggerPolicy2.setShouldTrigger(false);

        try {
            while (!pollForFinishCheckpointing()) {
                TimeUnit.MILLISECONDS.sleep(COMPACTOR_SERVICE_INTERVAL);
            }
        } catch (InterruptedException e) {
            log.warn("Sleep interrupted, ", e);
        }

        assert(verifyManagerStatus(StatusType.COMPLETED));
        assert(verifyCheckpointStatusTable(StatusType.COMPLETED, 0));
        verifyCheckpointTable();
    }

    private void shutdownServer(int port) {
        getLogUnit(port).shutdown();
        getSequencer(port).shutdown();
        getLayoutServer(port).shutdown();
        getManagementServer(port).shutdown();
    }

    /**
     * Refreshes the layout and waits for a limited time for the refreshed layout to satisfy the
     * expected epoch verifier.
     *
     * @param epochVerifier Predicate to test the refreshed epoch value.
     * @param corfuRuntime  Runtime.
     */
    private void waitForEpochChange(Predicate<Long> epochVerifier, CorfuRuntime corfuRuntime)
            throws InterruptedException {
        log.warn("Waiting....");
        corfuRuntime.invalidateLayout();
        Layout refreshedLayout = corfuRuntime.getLayoutView().getLayout();
        for (int i = 0; i < PARAMETERS.NUM_ITERATIONS_MODERATE; i++) {
            if (epochVerifier.test(refreshedLayout.getEpoch())) {
                break;
            }
            corfuRuntime.invalidateLayout();
            refreshedLayout = corfuRuntime.getLayoutView().getLayout();
            TimeUnit.MILLISECONDS.sleep(PARAMETERS.TIMEOUT_SHORT.toMillis());
        }
        log.warn("Waiting Done....{}", refreshedLayout.getEpoch());
        assertThat(epochVerifier.test(refreshedLayout.getEpoch())).isTrue();
    }

    private static final int N_THREADS = 3;

    @Test
    public void leaderFailureTest() {

        ExecutorService compactorServiceScheduler = Executors.newFixedThreadPool(N_THREADS);

        SingletonResource<CorfuRuntime> runtimeSingletonResource0 = SingletonResource.withInitial(() -> runtime0);
        MockCompactionTriggerPolicy mockCompactionTriggerPolicy0 = new MockCompactionTriggerPolicy();
        CompactorService compactorService0 = new CompactorService(sc0, runtimeSingletonResource0,
                new InvokeCheckpointingMock(runtime0, cpRuntime0), mockCompactionTriggerPolicy0);
        compactorService0.start(Duration.ofMillis(COMPACTOR_SERVICE_INTERVAL));
        compactorService0.setLIVENESS_TIMEOUT(LIVENESS_TIMEOUT);
        mockCompactionTriggerPolicy0.setShouldTrigger(true);

        SingletonResource<CorfuRuntime> runtimeSingletonResource1 = SingletonResource.withInitial(() -> runtime1);
        MockCompactionTriggerPolicy mockCompactionTriggerPolicy1 = new MockCompactionTriggerPolicy();
        CompactorService compactorService1 = new CompactorService(sc1, runtimeSingletonResource1,
                new InvokeCheckpointingMock(runtime1, cpRuntime1), mockCompactionTriggerPolicy1);
        compactorService1.start(Duration.ofMillis(COMPACTOR_SERVICE_INTERVAL));
        compactorService1.setLIVENESS_TIMEOUT(LIVENESS_TIMEOUT);
        mockCompactionTriggerPolicy1.setShouldTrigger(false);

        SingletonResource<CorfuRuntime> runtimeSingletonResource2 = SingletonResource.withInitial(() -> runtime2);
        MockCompactionTriggerPolicy mockCompactionTriggerPolicy2 = new MockCompactionTriggerPolicy();
        CompactorService compactorService2 = new CompactorService(sc2, runtimeSingletonResource2,
                new InvokeCheckpointingMock(runtime2, cpRuntime2), mockCompactionTriggerPolicy2);
        compactorService2.start(Duration.ofMillis(COMPACTOR_SERVICE_INTERVAL));
        compactorService2.setLIVENESS_TIMEOUT(LIVENESS_TIMEOUT);
        mockCompactionTriggerPolicy2.setShouldTrigger(false);

        try {
            TimeUnit.MILLISECONDS.sleep(LIVENESS_TIMEOUT);
            shutdownServer(SERVERS.PORT_0);
            compactorService0.shutdown();

            while (!pollForFinishCheckpointing()) {
                TimeUnit.MILLISECONDS.sleep(COMPACTOR_SERVICE_INTERVAL);
            }
            compactorService1.shutdown();
            compactorService2.shutdown();
        } catch (Exception e) {
            log.warn("Exception: ", e);
        }

        assert(verifyManagerStatus(StatusType.COMPLETED));
        assert(verifyCheckpointStatusTable(StatusType.COMPLETED, 0));
        assert(verifyCheckpointTable());
    }

    @Test
    public void nonLeaderFailureTest() {
        ExecutorService compactorServiceScheduler = Executors.newFixedThreadPool(N_THREADS);

        SingletonResource<CorfuRuntime> runtimeSingletonResource0 = SingletonResource.withInitial(() -> runtime0);
        MockCompactionTriggerPolicy mockCompactionTriggerPolicy0 = new MockCompactionTriggerPolicy();
        CompactorService compactorService0 = new CompactorService(sc0, runtimeSingletonResource0,
                new InvokeCheckpointingMock(runtime0, cpRuntime0), mockCompactionTriggerPolicy0);
        compactorService0.setLIVENESS_TIMEOUT(LIVENESS_TIMEOUT);
        mockCompactionTriggerPolicy0.setShouldTrigger(true);

        SingletonResource<CorfuRuntime> runtimeSingletonResource1 = SingletonResource.withInitial(() -> runtime1);
        MockCompactionTriggerPolicy mockCompactionTriggerPolicy1 = new MockCompactionTriggerPolicy();
        CompactorService compactorService1 = new CompactorService(sc1, runtimeSingletonResource1,
                new InvokeCheckpointingMock(runtime1, cpRuntime1), mockCompactionTriggerPolicy1);
        compactorService1.setLIVENESS_TIMEOUT(LIVENESS_TIMEOUT);
        mockCompactionTriggerPolicy1.setShouldTrigger(true);

        SingletonResource<CorfuRuntime> runtimeSingletonResource2 = SingletonResource.withInitial(() -> runtime2);
        MockCompactionTriggerPolicy mockCompactionTriggerPolicy2 = new MockCompactionTriggerPolicy();
        CompactorService compactorService2 = new CompactorService(sc2, runtimeSingletonResource2,
                new InvokeCheckpointingMock(runtime2, cpRuntime2), mockCompactionTriggerPolicy2);
        compactorService2.setLIVENESS_TIMEOUT(LIVENESS_TIMEOUT);
        mockCompactionTriggerPolicy2.setShouldTrigger(true);

        compactorServiceScheduler.submit(() -> compactorService0.start(Duration.ofMillis(COMPACTOR_SERVICE_INTERVAL)));
        Future<Boolean> future1 = (Future<Boolean>) compactorServiceScheduler.submit(() -> compactorService1.start(Duration.ofMillis(COMPACTOR_SERVICE_INTERVAL)));
        compactorServiceScheduler.submit(() -> compactorService2.start(Duration.ofMillis(COMPACTOR_SERVICE_INTERVAL)));

        System.out.println("First Layout : " + layout.getAllActiveServers().toString());

        try {
            TimeUnit.MILLISECONDS.sleep(COMPACTOR_SERVICE_INTERVAL);
            shutdownServer(SERVERS.PORT_1);
            compactorService0.shutdown();

            while (!pollForFinishCheckpointing()) {
                TimeUnit.MILLISECONDS.sleep(COMPACTOR_SERVICE_INTERVAL);
            }
            compactorService0.shutdown();
            compactorService2.shutdown();
            compactorService1.shutdown();
            compactorServiceScheduler.shutdownNow();
        } catch (Exception e) {
            log.warn("Exception: ", e);
        }

        assert(verifyManagerStatus(StatusType.COMPLETED));
        assert(verifyCheckpointStatusTable(StatusType.COMPLETED, 0));
        assert(verifyCheckpointTable());
    }
}