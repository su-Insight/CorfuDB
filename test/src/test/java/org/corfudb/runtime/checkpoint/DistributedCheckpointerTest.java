package org.corfudb.runtime.checkpoint;

import com.google.protobuf.Message;
import lombok.extern.slf4j.Slf4j;
import org.corfudb.infrastructure.CompactorLeaderServices;
import org.corfudb.infrastructure.CompactorLeaderServices.LeaderServicesStatus;
import org.corfudb.infrastructure.ServerContext;
import org.corfudb.infrastructure.ServerContextBuilder;
import org.corfudb.infrastructure.TestLayoutBuilder;
import org.corfudb.infrastructure.TestServerRouter;
import org.corfudb.runtime.*;
import org.corfudb.runtime.CorfuCompactorManagement.ActiveCPStreamMsg;
import org.corfudb.runtime.CorfuCompactorManagement.CheckpointingStatus;
import org.corfudb.runtime.CorfuCompactorManagement.CheckpointingStatus.StatusType;
import org.corfudb.runtime.CorfuCompactorManagement.StringKey;
import org.corfudb.runtime.CorfuStoreMetadata.TableName;
import org.corfudb.runtime.collections.CorfuStore;
import org.corfudb.runtime.collections.Table;
import org.corfudb.runtime.collections.TableOptions;
import org.corfudb.runtime.collections.TxnContext;
import org.corfudb.runtime.proto.RpcCommon.TokenMsg;
import org.corfudb.runtime.view.AbstractViewTest;
import org.corfudb.runtime.view.Layout;
import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.corfudb.runtime.view.TableRegistry.CORFU_SYSTEM_NAMESPACE;

@Slf4j
public class DistributedCheckpointerTest extends AbstractViewTest {

    private CorfuRuntime runtime0 = null;
    private CorfuRuntime runtime1 = null;
    private CorfuRuntime runtime2 = null;
    private CorfuRuntime cpRuntime0 = null;
    private CorfuRuntime cpRuntime1 = null;
    private CorfuRuntime cpRuntime2 = null;

    private CorfuStore corfuStore = null;

    private static final int WAIT_FOR_FINISH_CYCLE = 10;
    private static final int WAIT_IN_SYNC_STATE = 5000;
    private static final int LIVENESS_TIMEOUT = 1000;
    private static final String CLIENT_NAME_PREFIX = "Client";
    private static final String STREAM_NAME = "streamNameA";

    private static final String CACHE_SIZE_HEAP_RATIO = "0.0";
    private static final String OPEN_TABLES_EXCEPTION_MSG = "Exception while opening tables";
    private static final String SLEEP_INTERRUPTED_EXCEPTION_MSG = "Sleep interrupted";

    private MockLivenessUpdater mockLivenessUpdater;

    /**
     * Generates and bootstraps a 3 node cluster in disk mode.
     *
     * @return The generated layout.
     */
    private Layout setup3NodeCluster() {
        ServerContext sc0 = new ServerContextBuilder()
                .setSingle(false)
                .setServerRouter(new TestServerRouter(SERVERS.PORT_0))
                .setPort(SERVERS.PORT_0)
                .setMemory(false)
                .setCacheSizeHeapRatio(CACHE_SIZE_HEAP_RATIO)
                .setLogPath(com.google.common.io.Files.createTempDir().getAbsolutePath())
                .build();
        ServerContext sc1 = new ServerContextBuilder()
                .setSingle(false)
                .setServerRouter(new TestServerRouter(SERVERS.PORT_1))
                .setPort(SERVERS.PORT_1)
                .setMemory(false)
                .setCacheSizeHeapRatio(CACHE_SIZE_HEAP_RATIO)
                .setLogPath(com.google.common.io.Files.createTempDir().getAbsolutePath())
                .build();
        ServerContext sc2 = new ServerContextBuilder()
                .setSingle(false)
                .setServerRouter(new TestServerRouter(SERVERS.PORT_2))
                .setPort(SERVERS.PORT_2)
                .setMemory(false)
                .setCacheSizeHeapRatio(CACHE_SIZE_HEAP_RATIO)
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

        // Shutdown management servers.
        getManagementServer(SERVERS.PORT_0).shutdown();
        getManagementServer(SERVERS.PORT_1).shutdown();
        getManagementServer(SERVERS.PORT_2).shutdown();

        return l;
    }

    @Before
    public void testSetup() {
        Layout l = setup3NodeCluster();

        runtime0 = getRuntime(l).connect();
        runtime1 = getRuntime(l).connect();
        runtime2 = getRuntime(l).connect();
        runtime0.getParameters().setClientName(CLIENT_NAME_PREFIX + "0");
        runtime1.getParameters().setClientName(CLIENT_NAME_PREFIX + "1");
        runtime2.getParameters().setClientName(CLIENT_NAME_PREFIX + "2");

        cpRuntime0 = getRuntime(l).connect();
        cpRuntime1 = getRuntime(l).connect();
        cpRuntime2 = getRuntime(l).connect();
        cpRuntime0.getParameters().setClientName(CLIENT_NAME_PREFIX + "_cp0");
        cpRuntime1.getParameters().setClientName(CLIENT_NAME_PREFIX + "_cp1");
        cpRuntime2.getParameters().setClientName(CLIENT_NAME_PREFIX + "_cp2");


        corfuStore = new CorfuStore(runtime0);
        mockLivenessUpdater = new MockLivenessUpdater(corfuStore);
    }

    private Table<StringKey, CheckpointingStatus, Message> openCompactionManagerTable() {
        try {
            return corfuStore.openTable(CORFU_SYSTEM_NAMESPACE,
                    CompactorMetadataTables.COMPACTION_MANAGER_TABLE_NAME,
                    StringKey.class,
                    CheckpointingStatus.class,
                    null,
                    TableOptions.fromProtoSchema(CheckpointingStatus.class));
        } catch (Exception e) {
            log.error(OPEN_TABLES_EXCEPTION_MSG, e);
            return null;
        }
    }

    private Table<TableName, CheckpointingStatus, Message> openCheckpointStatusTable() {
        try {
            return corfuStore.openTable(CORFU_SYSTEM_NAMESPACE,
                    CompactorMetadataTables.CHECKPOINT_STATUS_TABLE_NAME,
                    TableName.class,
                    CheckpointingStatus.class,
                    null,
                    TableOptions.fromProtoSchema(CheckpointingStatus.class));
        } catch (Exception e) {
            log.error(OPEN_TABLES_EXCEPTION_MSG, e);
            return null;
        }
    }

    private Table<StringKey, TokenMsg, Message> openCheckpointTable() {
        try {
            return corfuStore.openTable(CORFU_SYSTEM_NAMESPACE,
                    CompactorMetadataTables.CHECKPOINT,
                    StringKey.class,
                    TokenMsg.class,
                    null,
                    TableOptions.fromProtoSchema(TokenMsg.class));
        } catch (Exception e) {
            log.error(OPEN_TABLES_EXCEPTION_MSG, e);
            return null;
        }
    }

    private Table<TableName, ActiveCPStreamMsg, Message> openActiveCheckpointsTable() {
        try {
            return corfuStore.openTable(CORFU_SYSTEM_NAMESPACE,
                    CompactorMetadataTables.ACTIVE_CHECKPOINTS_TABLE_NAME,
                    TableName.class,
                    ActiveCPStreamMsg.class,
                    null,
                    TableOptions.fromProtoSchema(ActiveCPStreamMsg.class));
        } catch (Exception e) {
            log.error(OPEN_TABLES_EXCEPTION_MSG, e);
            return null;
        }
    }

    private boolean verifyManagerStatus(StatusType targetStatus) {
        openCompactionManagerTable();
        try (TxnContext txn = corfuStore.txn(CORFU_SYSTEM_NAMESPACE)) {
            CheckpointingStatus managerStatus = (CheckpointingStatus) txn.getRecord(
                    CompactorMetadataTables.COMPACTION_MANAGER_TABLE_NAME,
                    CompactorMetadataTables.COMPACTION_MANAGER_KEY).getPayload();
            if (managerStatus.getStatus() == targetStatus) {
                return true;
            }
        }
        return false;
    }

    private boolean verifyCheckpointStatusTable(StatusType targetStatus, int maxFailedTables) {

        Table<TableName, CheckpointingStatus, Message> cpStatusTable = openCheckpointStatusTable();

        int failed = 0;
        try (TxnContext txn = corfuStore.txn(CORFU_SYSTEM_NAMESPACE)) {
            List<TableName> tableNames = new ArrayList<>(txn.keySet(cpStatusTable)
                    .stream().collect(Collectors.toList()));
            for (TableName table : tableNames) {
                CheckpointingStatus cpStatus = (CheckpointingStatus) txn.getRecord(
                        CompactorMetadataTables.CHECKPOINT_STATUS_TABLE_NAME, table).getPayload();
                log.info("table: {}, status: {}", table.getTableName(), cpStatus.getStatus());
                if (cpStatus.getStatus() != targetStatus) {
                    failed++;
                }
            }
            return failed <= maxFailedTables;
        }
    }

    private boolean verifyCheckpointTable() {

        openCheckpointTable();

        TokenMsg token;
        try (TxnContext txn = corfuStore.txn(CORFU_SYSTEM_NAMESPACE)) {
            token = (TokenMsg) txn.getRecord(CompactorMetadataTables.CHECKPOINT, CompactorMetadataTables.CHECKPOINT_KEY).getPayload();
            txn.commit();
        }

        return token != null;
    }

    @Test
    public void initTest() {
        CompactorLeaderServices compactorLeaderServices1 = new CompactorLeaderServices(runtime0, SERVERS.ENDPOINT_0, corfuStore);
        CompactorLeaderServices compactorLeaderServices2 = new CompactorLeaderServices(runtime1, SERVERS.ENDPOINT_1, corfuStore);

        assert compactorLeaderServices1.initCompactionCycle() == LeaderServicesStatus.SUCCESS;
        assert compactorLeaderServices2.initCompactionCycle() == LeaderServicesStatus.FAIL;
        assert verifyManagerStatus(StatusType.STARTED);
        assert verifyCheckpointStatusTable(StatusType.IDLE, 0);
    }

    @Test
    public void initMultipleLeadersTest1() {
        CompactorLeaderServices compactorLeaderServices1 = new CompactorLeaderServices(runtime0, SERVERS.ENDPOINT_0, corfuStore);
        CompactorLeaderServices compactorLeaderServices2 = new CompactorLeaderServices(runtime1, SERVERS.ENDPOINT_1, corfuStore);

        LeaderServicesStatus init1 = compactorLeaderServices1.initCompactionCycle();
        LeaderServicesStatus init2 = compactorLeaderServices2.initCompactionCycle();

        assert !init1.equals(init2);
        assert verifyManagerStatus(StatusType.STARTED);
        assert verifyCheckpointStatusTable(StatusType.IDLE, 0);
    }

    @Test
    public void initMultipleLeadersTest2() {
        CompactorLeaderServices compactorLeaderServices1 = new CompactorLeaderServices(runtime0, SERVERS.ENDPOINT_0, corfuStore);
        CompactorLeaderServices compactorLeaderServices2 = new CompactorLeaderServices(runtime1, SERVERS.ENDPOINT_1, corfuStore);

        ExecutorService scheduler = Executors.newFixedThreadPool(2);
        Future<LeaderServicesStatus> future1 = scheduler.submit(compactorLeaderServices1::initCompactionCycle);
        Future<LeaderServicesStatus> future2 = scheduler.submit(compactorLeaderServices2::initCompactionCycle);

        try {
            if (!future1.get().equals(future2.get())) {
                assert verifyManagerStatus(StatusType.STARTED);
                assert verifyCheckpointStatusTable(StatusType.IDLE, 0);
            } else {
                assert future1.get() != LeaderServicesStatus.SUCCESS && future2.get() != LeaderServicesStatus.SUCCESS;
            }
        } catch (Exception e) {
            log.warn("Unable to get results");
        }
    }

    @Test
    public void checkpointTablesTest() {
        CompactorLeaderServices compactorLeaderServices1 = new CompactorLeaderServices(runtime0, SERVERS.ENDPOINT_0, corfuStore);
        compactorLeaderServices1.initCompactionCycle();
        ServerTriggeredCheckpointer distributedCheckpointer1 =
                new ServerTriggeredCheckpointer(CheckpointerBuilder.builder()
                        .corfuRuntime(runtime0)
                        .cpRuntime(Optional.of(cpRuntime0))
                        .isClient(false)
                        .persistedCacheRoot(Optional.empty())
                        .build());
        ServerTriggeredCheckpointer distributedCheckpointer2 =
                new ServerTriggeredCheckpointer(CheckpointerBuilder.builder()
                        .corfuRuntime(runtime1)
                        .cpRuntime(Optional.of(cpRuntime1))
                        .isClient(false)
                        .persistedCacheRoot(Optional.empty())
                        .build());
        ServerTriggeredCheckpointer distributedCheckpointer3 =
                new ServerTriggeredCheckpointer(CheckpointerBuilder.builder()
                        .corfuRuntime(runtime2)
                        .cpRuntime(Optional.of(cpRuntime2))
                        .isClient(false)
                        .persistedCacheRoot(Optional.empty())
                        .build());

        distributedCheckpointer1.checkpointTables();
        distributedCheckpointer2.checkpointTables();
        distributedCheckpointer3.checkpointTables();
//        int total = count1 + count2 + count3;

//        try (TxnContext txn = corfuStore.txn(CORFU_SYSTEM_NAMESPACE)) {
//            //This assert ensures each table is checkpointed by only one of the clients
//            Assert.assertEquals(txn.count(CompactorMetadataTables.CHECKPOINT_STATUS_TABLE_NAME), total);
//            txn.commit();
//        }
        assert verifyManagerStatus(StatusType.STARTED);
        assert verifyCheckpointStatusTable(StatusType.COMPLETED, 0);
    }

    @Test
    public void finishCompactionCycleSuccessTest() {
        CompactorLeaderServices compactorLeaderServices1 = new CompactorLeaderServices(runtime0, SERVERS.ENDPOINT_0, corfuStore);
        compactorLeaderServices1.initCompactionCycle();

        ServerTriggeredCheckpointer distributedCheckpointer = new ServerTriggeredCheckpointer(CheckpointerBuilder.builder()
                .corfuRuntime(runtime0)
                .cpRuntime(Optional.of(cpRuntime0))
                .isClient(false)
                .persistedCacheRoot(Optional.empty())
                .build());
        distributedCheckpointer.checkpointTables();

        compactorLeaderServices1.finishCompactionCycle();

//        assert verifyManagerStatus(StatusType.COMPLETED);
        assert verifyCheckpointStatusTable(StatusType.COMPLETED, 0);
        assert verifyCheckpointTable();
    }

    @Test
    public void finishCompactionCycleFailureTest() {
        CompactorLeaderServices compactorLeaderServices1 = new CompactorLeaderServices(runtime0, SERVERS.ENDPOINT_0, corfuStore);
        compactorLeaderServices1.initCompactionCycle();

        //Checkpointing not done
        compactorLeaderServices1.finishCompactionCycle();

        assert verifyManagerStatus(StatusType.FAILED);
        assert verifyCheckpointStatusTable(StatusType.IDLE, 0);
    }

    private boolean pollForFinishCheckpointing() {
        try (TxnContext txn = corfuStore.txn(CORFU_SYSTEM_NAMESPACE)) {
            CheckpointingStatus managerStatus = (CheckpointingStatus) txn.getRecord(
                    CompactorMetadataTables.COMPACTION_MANAGER_TABLE_NAME,
                    CompactorMetadataTables.COMPACTION_MANAGER_KEY).getPayload();
            txn.commit();
            log.debug("managerStatus in test: {}", managerStatus == null ? "null" : managerStatus.getStatus());
            if (managerStatus != null && (managerStatus.getStatus() == StatusType.COMPLETED
                    || managerStatus.getStatus() == StatusType.FAILED)) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void validateLivenessLeaderTest() {
        //make some client to start cp
        //verifyCheckpointStatusTable
        CompactorLeaderServices.setLivenessTimeout(Duration.ofMillis(LIVENESS_TIMEOUT));
        CompactorLeaderServices compactorLeaderServices1 = new CompactorLeaderServices(runtime0, SERVERS.ENDPOINT_0, corfuStore);
        compactorLeaderServices1.initCompactionCycle();

        ServerTriggeredCheckpointer distributedCheckpointer = new ServerTriggeredCheckpointer(CheckpointerBuilder.builder()
                .corfuRuntime(runtime0)
                .cpRuntime(Optional.of(cpRuntime0))
                .isClient(false)
                .persistedCacheRoot(Optional.empty())
                .build());
        distributedCheckpointer.checkpointTables();

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(compactorLeaderServices1::validateLiveness, 0,
                LIVENESS_TIMEOUT, TimeUnit.MILLISECONDS);
        try {
            while (!pollForFinishCheckpointing()) {
                TimeUnit.MILLISECONDS.sleep(WAIT_FOR_FINISH_CYCLE);
            }
        } catch (InterruptedException e) {
            log.warn(SLEEP_INTERRUPTED_EXCEPTION_MSG, e);
        }

        assert verifyManagerStatus(StatusType.COMPLETED);
        assert verifyCheckpointStatusTable(StatusType.COMPLETED, 0);
        assert verifyCheckpointTable();
    }

    @Test
    public void validateLivenessNonLeaderTest() {
        CompactorLeaderServices.setLivenessTimeout(Duration.ofMillis(LIVENESS_TIMEOUT));
        CompactorLeaderServices compactorLeaderServices1 = new CompactorLeaderServices(runtime0, SERVERS.ENDPOINT_0, corfuStore);
        compactorLeaderServices1.initCompactionCycle();

        ServerTriggeredCheckpointer distributedCheckpointer = new ServerTriggeredCheckpointer(CheckpointerBuilder.builder()
                .corfuRuntime(runtime0)
                .cpRuntime(Optional.of(cpRuntime0))
                .isClient(false)
                .persistedCacheRoot(Optional.empty())
                .build());
        distributedCheckpointer.checkpointTables();

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(compactorLeaderServices1::validateLiveness, 0,
                LIVENESS_TIMEOUT, TimeUnit.MILLISECONDS);

        try {
            TimeUnit.MILLISECONDS.sleep(LIVENESS_TIMEOUT);
        } catch (InterruptedException e) {
            log.warn(SLEEP_INTERRUPTED_EXCEPTION_MSG, e);
        }

        assert verifyManagerStatus(StatusType.STARTED);
        assert verifyCheckpointStatusTable(StatusType.COMPLETED, 0);
    }

    @Test
    public void validateLivenessFailureTest() {
        CompactorLeaderServices.setLivenessTimeout(Duration.ofMillis(LIVENESS_TIMEOUT));
        CompactorLeaderServices compactorLeaderServices1 = new CompactorLeaderServices(runtime1, SERVERS.ENDPOINT_0, corfuStore);
        compactorLeaderServices1.initCompactionCycle();

        Table<TableName, ActiveCPStreamMsg, Message> activeCheckpointTable = openActiveCheckpointsTable();
        Table<TableName, CheckpointingStatus, Message> checkpointStatusTable = openCheckpointStatusTable();
        try (TxnContext txn = corfuStore.txn(CORFU_SYSTEM_NAMESPACE)) {
            TableName table = TableName.newBuilder().setNamespace(CORFU_SYSTEM_NAMESPACE).setTableName(STREAM_NAME).build();
            //Adding a table with STARTED value - making it look like someone started and died while checkpointing
            txn.putRecord(checkpointStatusTable, table,
                    CheckpointingStatus.newBuilder().setStatusValue(StatusType.STARTED_VALUE).build(), null);
            txn.putRecord(activeCheckpointTable,
                    table,
                    ActiveCPStreamMsg.getDefaultInstance(),
                    null);
            txn.commit();
        }

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(compactorLeaderServices1::validateLiveness, 0,
                LIVENESS_TIMEOUT, TimeUnit.MILLISECONDS);

        ServerTriggeredCheckpointer distributedCheckpointer = new ServerTriggeredCheckpointer(CheckpointerBuilder.builder()
                .corfuRuntime(runtime0)
                .cpRuntime(Optional.of(cpRuntime0))
                .isClient(false)
                .persistedCacheRoot(Optional.empty())
                .build());
        distributedCheckpointer.checkpointTables();

        try {
            while (!pollForFinishCheckpointing()) {
                TimeUnit.MILLISECONDS.sleep(WAIT_FOR_FINISH_CYCLE);
            }
        } catch (InterruptedException e) {
            log.warn(SLEEP_INTERRUPTED_EXCEPTION_MSG, e);
        }
        assert verifyManagerStatus(StatusType.FAILED);
        assert verifyCheckpointStatusTable(StatusType.COMPLETED, 1);
    }

    @Test
    public void validateLivenessSyncStateTest() {
        CompactorLeaderServices.setLivenessTimeout(Duration.ofMillis(LIVENESS_TIMEOUT));
        CompactorLeaderServices compactorLeaderServices1 = new CompactorLeaderServices(runtime1, SERVERS.ENDPOINT_0, corfuStore);
        compactorLeaderServices1.initCompactionCycle();

        Table<TableName, ActiveCPStreamMsg, Message> activeCheckpointTable = openActiveCheckpointsTable();
        Table<TableName, CheckpointingStatus, Message> checkpointStatusTable = openCheckpointStatusTable();
        try (TxnContext txn = corfuStore.txn(CORFU_SYSTEM_NAMESPACE)) {
            TableName table = TableName.newBuilder().setNamespace(CORFU_SYSTEM_NAMESPACE).setTableName(STREAM_NAME).build();
            txn.putRecord(checkpointStatusTable, table,
                    CheckpointingStatus.newBuilder().setStatusValue(StatusType.STARTED_VALUE).build(), null);
            txn.putRecord(activeCheckpointTable,
                    table,
                    ActiveCPStreamMsg.getDefaultInstance(),
                    null);
            txn.commit();
            mockLivenessUpdater.updateLiveness(table);
        }

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(compactorLeaderServices1::validateLiveness, 0,
                LIVENESS_TIMEOUT, TimeUnit.MILLISECONDS);

        ServerTriggeredCheckpointer distributedCheckpointer = new ServerTriggeredCheckpointer(CheckpointerBuilder.builder()
                .corfuRuntime(runtime0)
                .cpRuntime(Optional.of(cpRuntime0))
                .isClient(false)
                .persistedCacheRoot(Optional.empty())
                .build());

        try {
            TimeUnit.MILLISECONDS.sleep(WAIT_IN_SYNC_STATE);
            mockLivenessUpdater.notifyOnSyncComplete();

            distributedCheckpointer.checkpointTables();
            while (!pollForFinishCheckpointing()) {
                TimeUnit.MILLISECONDS.sleep(WAIT_FOR_FINISH_CYCLE);
            }
        } catch (InterruptedException e) {
            log.warn(SLEEP_INTERRUPTED_EXCEPTION_MSG, e);
        }

        assert verifyManagerStatus(StatusType.COMPLETED);
        assert verifyCheckpointStatusTable(StatusType.COMPLETED, 0);
    }
}