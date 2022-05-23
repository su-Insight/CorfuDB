package org.corfudb.infrastructure;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import lombok.NonNull;
import lombok.Setter;
import org.corfudb.runtime.CorfuCompactorManagement.CheckpointingStatus;
import org.corfudb.runtime.CorfuCompactorManagement.CheckpointingStatus.StatusType;
import org.corfudb.runtime.CorfuRuntime;
import org.corfudb.runtime.CorfuStoreMetadata.TableName;
import org.corfudb.runtime.DistributedCompactor;
import org.corfudb.runtime.collections.CorfuStore;
import org.corfudb.runtime.collections.TxnContext;
import org.corfudb.runtime.view.Layout;
import org.corfudb.util.concurrent.SingletonResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.corfudb.runtime.view.TableRegistry.CORFU_SYSTEM_NAMESPACE;

/**
 * Orchestrates distributed compaction
 * <p>
 * Created by Sundar Sridharan on 3/2/22.
 */
public class CompactorService implements ManagementService {

    private long compactionTriggerFreqMs = TimeUnit.MINUTES.toMillis(8);

    @Setter
    private static Duration livenessTimeout = Duration.ofMillis(60000);

    private final ServerContext serverContext;
    private final SingletonResource<CorfuRuntime> runtimeSingletonResource;

    private final ScheduledExecutorService orchestratorThread;
    private final IInvokeCheckpointing checkpointerJvmManager;

    private final ICompactionTriggerPolicy compactionTriggerPolicy;
    private CompactorLeaderServices compactorLeaderServices;
    private CorfuStore corfuStore;

    private final Logger syslog;

    //TODO: make it a prop file and maybe pass it from the server
    List<TableName> sensitiveTables = new ArrayList<>();

    CompactorService(@NonNull ServerContext serverContext,
                     @NonNull SingletonResource<CorfuRuntime> runtimeSingletonResource,
                     @NonNull IInvokeCheckpointing checkpointerJvmManager,
                     @NonNull ICompactionTriggerPolicy compactionTriggerPolicy) {
        this.serverContext = serverContext;
        this.runtimeSingletonResource = runtimeSingletonResource;

        String threadName;
        if (serverContext.getServerConfig().get("<port>").toString().equals("9040")) {
            threadName = "Cmpt-NonConfig-chkpter";
        } else {
            threadName = "Cmpt-Config-chkpter";
        }
        this.orchestratorThread = Executors.newSingleThreadScheduledExecutor(
                new ThreadFactoryBuilder()
                        .setNameFormat(threadName)
                        .build());
        this.checkpointerJvmManager = checkpointerJvmManager;
        this.compactionTriggerPolicy = compactionTriggerPolicy;
        syslog = LoggerFactory.getLogger("syslog");
    }

    CorfuRuntime getCorfuRuntime() {
        return runtimeSingletonResource.get();
    }

    /**
     * Starts the long running service.
     *
     * @param interval interval to run the service
     */
    @Override
    public void start(Duration interval) {
        this.compactorLeaderServices = new CompactorLeaderServices(getCorfuRuntime(), serverContext.getLocalEndpoint());
        this.corfuStore = new CorfuStore(getCorfuRuntime());
        this.compactionTriggerPolicy.setCorfuRuntime(getCorfuRuntime());
        if (getCorfuRuntime().getParameters().getCheckpointTriggerFreqMillis() > 0) {
            this.compactionTriggerFreqMs = getCorfuRuntime().getParameters().getCheckpointTriggerFreqMillis();
        }

        orchestratorThread.scheduleWithFixedDelay(
                this::runOrchestrator,
                interval.toMillis(),
                interval.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    /**
     * Invokes and cleans up the CorfuStoreCompactor jvm based on the status of CompactionManager
     * Additionally, If the current node is the leader,
     * a. Invokes ValidateLiveness() to keep track of checkpointing progress by each client
     * b. Triggers the distributed compaction cycle based on the TriggerPolicy
     */
    private void runOrchestrator() {
        boolean isLeader = isNodePrimarySequencer(updateLayoutAndGet());
        compactorLeaderServices.setLeader(isLeader);
        CheckpointingStatus managerStatus = null;
        try (TxnContext txn = corfuStore.txn(CORFU_SYSTEM_NAMESPACE)) {
            managerStatus = (CheckpointingStatus) txn.getRecord(
                    DistributedCompactor.COMPACTION_MANAGER_TABLE_NAME,
                    DistributedCompactor.COMPACTION_MANAGER_KEY).getPayload();
            txn.commit();
        } catch (Exception e) {
            syslog.warn("Exception in runOrchestrator: {}", e.getStackTrace());
        }
        syslog.trace("ManagerStatus: {}", managerStatus == null ? "null" : managerStatus.getStatus());
        if (managerStatus != null) {
            if (managerStatus.getStatus() == StatusType.FAILED || managerStatus.getStatus() == StatusType.COMPLETED) {
                checkpointerJvmManager.shutdown();
                checkpointerJvmManager.setIsInvoked(false);
            } else if (managerStatus.getStatus() == StatusType.STARTED_ALL && !checkpointerJvmManager.isRunning()
                    && !checkpointerJvmManager.isInvoked()) {
                checkpointerJvmManager.invokeCheckpointing();
            }
        }

        if (isLeader) {
            if (managerStatus != null && (managerStatus.getStatus() == StatusType.STARTED ||
                    managerStatus.getStatus() == StatusType.STARTED_ALL)) {
                compactorLeaderServices.validateLiveness(livenessTimeout.toMillis());
            } else if (compactionTriggerPolicy.shouldTrigger(this.compactionTriggerFreqMs)) {
                compactionTriggerPolicy.markCompactionCycleStart();
                compactorLeaderServices.trimAndTriggerDistributedCheckpointing();
            }
        }
    }

    private Layout updateLayoutAndGet() {
        return getCorfuRuntime()
                .invalidateLayout()
                .join();
    }

    private boolean isNodePrimarySequencer(Layout layout) {
        return layout.getPrimarySequencer().equals(serverContext.getLocalEndpoint());
    }

    /**
     * Clean up.
     */
    @Override
    public void shutdown() {
        checkpointerJvmManager.shutdown();
        orchestratorThread.shutdownNow();
        syslog.info("Compactor Orchestrator service shutting down.");
    }
}
