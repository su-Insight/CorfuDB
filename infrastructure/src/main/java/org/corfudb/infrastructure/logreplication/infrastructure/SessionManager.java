package org.corfudb.infrastructure.logreplication.infrastructure;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Sets;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.corfudb.infrastructure.ServerContext;
import org.corfudb.infrastructure.logreplication.infrastructure.msghandlers.LogReplicationServer;
import org.corfudb.infrastructure.logreplication.replication.receive.LogReplicationMetadataManager;
import org.corfudb.infrastructure.logreplication.proto.LogReplicationMetadata.ReplicationMetadata;
import org.corfudb.infrastructure.logreplication.runtime.LogReplicationClientServerRouter;
import org.corfudb.infrastructure.logreplication.runtime.fsm.sink.RemoteSourceLeadershipManager;
import org.corfudb.infrastructure.logreplication.utils.LogReplicationConfigManager;
import org.corfudb.infrastructure.logreplication.utils.LogReplicationUpgradeManager;
import org.corfudb.runtime.CorfuRuntime;
import org.corfudb.runtime.LogReplication.ReplicationStatus;
import org.corfudb.runtime.LogReplication.ReplicationModel;
import org.corfudb.runtime.LogReplication.LogReplicationSession;
import org.corfudb.runtime.LogReplication.ReplicationSubscriber;
import org.corfudb.runtime.collections.CorfuStore;
import org.corfudb.runtime.collections.TxnContext;
import org.corfudb.runtime.exceptions.TransactionAbortedException;
import org.corfudb.runtime.exceptions.unrecoverable.UnrecoverableCorfuInterruptedError;
import org.corfudb.util.NodeLocator;
import org.corfudb.util.retry.IRetry;
import org.corfudb.util.retry.IntervalRetry;
import org.corfudb.util.retry.RetryNeededException;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.corfudb.infrastructure.logreplication.replication.receive.LogReplicationMetadataManager.METADATA_TABLE_NAME;

/**
 * Manage log replication sessions for multiple replication models.
 *
 * A replication session is determined by the cluster endpoints (source & sink)--given by the
 * Cluster Manager (topology)--the replication model and the client (given by clients upon
 * explicit registration or implicitly through protoBuf schema options).
 *
 * The access to the methods in this class is single threaded, but multiple threads will read from the data structures
 * containing the session: sessions, incomingSessions and outgoingSessions
 */
@Slf4j
public class SessionManager {

    // Represents default client for LR V1, i.e., use case where tables are tagged with
    // 'is_federated' flag, yet no client is specified in proto
    private static final String DEFAULT_CLIENT = "00000000-0000-0000-0000-000000000000";

    private final CorfuStore corfuStore;

    private final CorfuRuntime runtime;

    private final String localCorfuEndpoint;

    private CorfuReplicationManager replicationManager;

    private final LogReplicationConfigManager configManager;

    @Getter
    private final Set<LogReplicationSession> sessions = ConcurrentHashMap.newKeySet();

    private final Set<LogReplicationSession> incomingSessions = ConcurrentHashMap.newKeySet();

    private final Set<LogReplicationSession> outgoingSessions = ConcurrentHashMap.newKeySet();

    private final Set<LogReplicationSession> newSessionsDiscovered = new HashSet<>();

    @Getter
    private TopologyDescriptor topology;

    @Getter
    private final LogReplicationMetadataManager metadataManager;

    private final LogReplicationUpgradeManager upgradeManager;

    @Getter
    private final LogReplicationContext replicationContext;

    @Getter
    private LogReplicationClientServerRouter router;

    private LogReplicationServer incomingMsgHandler;

    /**
     * Constructor
     *
     * @param topology            the current topology
     * @param corfuRuntime        runtime for database access
     * @param serverContext       the server context
     * @param upgradeManager      upgrade management module
     */
    public SessionManager(@Nonnull TopologyDescriptor topology, CorfuRuntime corfuRuntime,
                          ServerContext serverContext, LogReplicationUpgradeManager upgradeManager) {
        this.topology = topology;
        this.runtime = corfuRuntime;
        this.corfuStore = new CorfuStore(corfuRuntime);

        // serverContext.getLocalEndpoint currently provides a string with address = localhost and port = LR server
        // port.  But the endpoint needed here is with the Corfu port, i.e., 9000.  So create a NodeLocator
        // using the endpoint available from serverContext first and then create a new NodeLocator which replaces the
        // port with the Corfu port.
        // TODO: Once IPv6 changes are available, the 2nd NodeLocator need not be created.  A utility method which
        //  generates the endpoint given an IP address and port - getVersionFormattedHostAddressWithPort() - should be
        //  used
        NodeLocator nodeLocator = NodeLocator.parseString(serverContext.getLocalEndpoint());
        NodeLocator lrNodeLocator = NodeLocator.builder().host(nodeLocator.getHost())
                .port(topology.getLocalClusterDescriptor().getCorfuPort())
                .build();

        this.localCorfuEndpoint = lrNodeLocator.toEndpointUrl();

        this.configManager = new LogReplicationConfigManager(runtime, serverContext);
        this.upgradeManager = upgradeManager;
        this.replicationContext = new LogReplicationContext(configManager, topology.getTopologyConfigId(),
                localCorfuEndpoint);
        this.metadataManager = new LogReplicationMetadataManager(corfuRuntime, replicationContext);

        this.replicationManager = new CorfuReplicationManager(topology, metadataManager,
                configManager.getServerContext().getPluginConfigFilePath(), runtime, upgradeManager,
                replicationContext);

        this.incomingMsgHandler = new LogReplicationServer(serverContext, sessions, metadataManager,
                topology.getLocalNodeDescriptor().getNodeId(), topology.getLocalNodeDescriptor().getClusterId(),
                replicationContext);

        this.router = new LogReplicationClientServerRouter(
                runtime.getParameters().getRequestTimeout().toMillis(), replicationManager,
                topology.getLocalNodeDescriptor().getClusterId(), topology.getLocalNodeDescriptor().getNodeId(),
                incomingSessions, outgoingSessions, incomingMsgHandler, replicationManager.getSessionRuntimeMap());
    }

    /**
     * Test constructor.
     *
     * @param topology            the current topology
     * @param corfuRuntime        runtime for database access
     */
    @VisibleForTesting
    public SessionManager(@Nonnull TopologyDescriptor topology, CorfuRuntime corfuRuntime,
                          CorfuReplicationManager replicationManager, LogReplicationClientServerRouter router,
                          LogReplicationServer logReplicationServer) {
        this.topology = topology;
        this.runtime = corfuRuntime;
        this.corfuStore = new CorfuStore(corfuRuntime);

        NodeLocator lrNodeLocator = NodeLocator.builder().host("localhost")
            .port(topology.getLocalClusterDescriptor().getCorfuPort())
            .build();
        this.localCorfuEndpoint = lrNodeLocator.toEndpointUrl();
        this.configManager = new LogReplicationConfigManager(runtime);
        this.upgradeManager = null;
        this.replicationContext = new LogReplicationContext(configManager, topology.getTopologyConfigId(), localCorfuEndpoint);
        this.metadataManager = new LogReplicationMetadataManager(corfuRuntime, replicationContext);
        this.replicationManager = replicationManager;
        this.router = router;
        this.incomingMsgHandler = logReplicationServer;
    }


    /**
     * Refresh sessions based on new topology :
     * (i) For sessions that are still valid, update the topology
     * (ii) For newly added clusters, create sessions, update internal tables, and create appropriate LR components
     * (iii) For a cluster that was removed, identify the (now) stale sessions, stop replication and clear the cached
     * information pertaining to the stale sessions
     *
     * The metadata updates will be done only by the leader node, so other nodes do not overwrite the data
     *
     * @param newTopology   the new discovered topology
     */
    public synchronized void refresh(@Nonnull TopologyDescriptor newTopology) {

        Set<String> newRemoteSources = newTopology.getRemoteSourceClusters().keySet();
        Set<String> currentRemoteSources = topology.getRemoteSourceClusters().keySet();
        Set<String> remoteSourcesToRemove = Sets.difference(currentRemoteSources, newRemoteSources);
        Set<String> remoteSourceClustersUnchanged = Sets.intersection(newRemoteSources, currentRemoteSources);

        Set<String> newRemoteSinks = newTopology.getRemoteSinkClusters().keySet();
        Set<String> currentRemoteSinks = topology.getRemoteSinkClusters().keySet();
        Set<String> remoteSinksToRemove = Sets.difference(currentRemoteSinks, newRemoteSinks);

        Set<LogReplicationSession> sessionsToRemove = new HashSet<>();
        Set<LogReplicationSession> sessionsUnchanged = new HashSet<>();

        try {
            IRetry.build(IntervalRetry.class, () -> {
                try (TxnContext txn = corfuStore.txn(LogReplicationMetadataManager.NAMESPACE)) {
                    sessions.forEach(session -> {
                        if (remoteSinksToRemove.contains(session.getSinkClusterId()) ||
                                remoteSourcesToRemove.contains(session.getSourceClusterId())) {
                            sessionsToRemove.add(session);
                            metadataManager.removeSession(txn, session);
                        } else {
                            sessionsUnchanged.add(session);

                            //update topologyId for sink sessions
                            if (remoteSourceClustersUnchanged.contains(session.getSourceClusterId())) {
                                metadataManager.updateReplicationMetadataField(txn, session,
                                        ReplicationMetadata.TOPOLOGYCONFIGID_FIELD_NUMBER, newTopology.getTopologyConfigId());
                            }
                        }
                    });
                    txn.commit();
                } catch (TransactionAbortedException e) {
                    throw new RetryNeededException();
                }

                updateTopology(newTopology);
                stopReplication(sessionsToRemove);
                updateReplicationParameters(Sets.intersection(sessionsUnchanged, outgoingSessions));
                createSessions();
                return null;
            }).run();
        } catch (InterruptedException e) {
            log.error("Unrecoverable exception while refreshing sessions", e);
            throw new UnrecoverableCorfuInterruptedError(e);
        }
    }

    /**
     * Update in-memory topology and topologyId
     * @param newTopology new topology
     */
    private void updateTopology(TopologyDescriptor newTopology) {
        topology = newTopology;
        router.setTopology(topology);
        replicationManager.updateTopology(topology);
        incomingMsgHandler.updateTopologyConfigId(topology.getTopologyConfigId());
        replicationContext.setTopologyConfigId(topology.getTopologyConfigId());
    }

    private void updateReplicationParameters(Set<LogReplicationSession> sessionsUnchanged) {
        if (replicationManager == null) {
            return;
        }
        for (LogReplicationSession session : sessionsUnchanged) {
            ClusterDescriptor cluster = topology.getRemoteSinkClusters().get(session.getSourceClusterId());
            replicationManager.refreshRuntime(session, cluster, topology.getTopologyConfigId());
        }
    }

    /**
     * Create sessions (outgoing and incoming) along with metadata
     *
     */
    private void createSessions() {
        // sessions where the local cluster is the Sink
        Set<LogReplicationSession> incomingSessionsToAdd = new HashSet<>();
        // sessions where the local cluster is the Source
        Set<LogReplicationSession> outgoingSessionsToAdd = new HashSet<>();
        // clear any prior session.
        newSessionsDiscovered.clear();

        try {
            String localClusterId = topology.getLocalClusterDescriptor().getClusterId();

            IRetry.build(IntervalRetry.class, () -> {
                try (TxnContext txn = corfuStore.txn(LogReplicationMetadataManager.NAMESPACE)) {

                    for(ClusterDescriptor remoteSinkCluster : topology.getRemoteSinkClusters().values()) {
                        LogReplicationSession session = constructSession(localClusterId, remoteSinkCluster.clusterId);
                        if (!sessions.contains(session)) {
                            newSessionsDiscovered.add(session);
                            outgoingSessionsToAdd.add(session);
                            metadataManager.addSession(txn, session, topology.getTopologyConfigId(), false);
                        }
                    }

                    for(ClusterDescriptor remoteSourceCluster : topology.getRemoteSourceClusters().values()) {
                        LogReplicationSession session = constructSession(remoteSourceCluster.getClusterId(), localClusterId);
                        if (!sessions.contains(session)) {
                            newSessionsDiscovered.add(session);
                            incomingSessionsToAdd.add(session);
                            metadataManager.addSession(txn, session, topology.getTopologyConfigId(), true);
                        }
                    }

                    txn.commit();
                    return null;
                } catch (TransactionAbortedException e) {
                    log.error("Failed to create sessions.  Retrying.", e);
                    throw new RetryNeededException();
                }
            }).run();
        } catch (InterruptedException e) {
            log.error("Unrecoverable Corfu Error when creating the sessions", e);
            throw new UnrecoverableCorfuInterruptedError(e);
        }

        sessions.addAll(newSessionsDiscovered);
        incomingSessions.addAll(incomingSessionsToAdd);
        outgoingSessions.addAll(outgoingSessionsToAdd);
        updateRouterWithNewSessions();
        createSourceFSMs();

        // TODO(V2): The below method logs a mapping of a session's hashcode to the session itself.  This
        //  is because LR names worker threads corresponding to a session using its hashcode.
        //  To identify the remote cluster and replication information while debugging, we need a view of
        //  the whole session object.  However, this method of logging the mapping has limitations
        //  because the mapping is only printed when the session is created for the first time.  If the
        //  logs have rolled over, the information is lost.  We need a permanent store/log of this mapping.
        logNewlyAddedSessionInfo();

        log.info("Total sessions={}, outgoing={}, incoming={}, sessions={}", sessions.size(), outgoingSessions.size(),
                incomingSessions.size(), sessions);
    }


    private void logNewlyAddedSessionInfo() {
        log.info("========= HashCode -> Session mapping for newly added session: =========");
        for (LogReplicationSession session : newSessionsDiscovered) {
            log.info("HashCode: {}, Session: {}", session.hashCode(), session);
        }
    }

    private void updateRouterWithNewSessions() {
        newSessionsDiscovered.forEach(session -> {
            router.getSessionToRequestIdCounter().put(session, new AtomicLong(0));
            if(incomingSessions.contains(session)) {
                router.getSessionToRemoteClusterDescriptor()
                        .put(session, topology.getRemoteSourceClusters().get(session.getSourceClusterId()));
                //create sink managers for incoming sessions
                incomingMsgHandler.createSinkManager(session);
            } else {
                router.getSessionToRemoteClusterDescriptor()
                        .put(session, topology.getRemoteSinkClusters().get(session.getSinkClusterId()));
                router.getSessionToOutstandingRequests().put(session, new HashMap<>());
            }

            if(router.isConnectionStarterForSession(session)) {
                router.getSessionToLeaderConnectionFuture().put(session, new CompletableFuture<>());
                router.getSessionToOutstandingRequests().putIfAbsent(session, new HashMap<>());
                if (incomingSessions.contains(session)) {
                    router.getSessionToRemoteSourceLeaderManager().put(session,
                            new RemoteSourceLeadershipManager(session, router,
                                    topology.getLocalNodeDescriptor().getNodeId()));
                }
            }
        });
    }

    /**
     * Construct session.
     */
    // TODO(V2): for now only creating sessions for FULL TABLE replication model (assumed as default)
    private LogReplicationSession constructSession(String sourceClusterId, String sinkClusterId) {
        return LogReplicationSession.newBuilder()
                .setSourceClusterId(sourceClusterId)
                .setSinkClusterId(sinkClusterId)
                .setSubscriber(getDefaultSubscriber())
                .build();
    }

    /**
     * When a node acquires the lock, check if all the sessions present in the system tables are still valid.
     *
     * Since the metadata table is written to only by the leader node, we can run into the scenario where the leader node
     * missed updating the system table because the leadership changed at that precise moment.
     * If the new leader node had already received the topology change notification before becoming the leader, the stale
     * sessions would not be recognized on "refresh".
     * Therefore, iterate over the sessions in the system tables and remove the stale sessions if any.
     */
    public void removeStaleSessionOnLeadershipAcquire() {
        try {
            IRetry.build(IntervalRetry.class, () -> {
                try (TxnContext txn = metadataManager.getTxnContext()) {
                    Set<LogReplicationSession> sessionsInTable = txn.keySet(METADATA_TABLE_NAME);
                    sessionsInTable.stream().filter(sessionInTable -> !sessions.contains(sessionInTable))
                            .forEach(staleSession -> metadataManager.removeSession(txn, staleSession));
                    txn.commit();
                    return null;
                } catch (TransactionAbortedException e) {
                    log.error("Failed to create sessions.  Retrying.", e);
                    throw new RetryNeededException();
                }
            }).run();
        } catch (InterruptedException e) {
            log.error("Unrecoverable Corfu Error when removing stale sessions from LR internal tables", e);
            throw new UnrecoverableCorfuInterruptedError(e);
        }
    }

    /**
     * Retrieve all sessions for which this cluster is the sink
     *
     * @return set of sessions for which this cluster is the sink
     */
    public Set<LogReplicationSession> getIncomingSessions() {
        return incomingSessions;
    }

    /**
     * Retrieve all sessions for which this cluster is the source
     *
     * @return set of sessions for which this cluster is the sink
     */
    public Set<LogReplicationSession> getOutgoingSessions() {
        return outgoingSessions;
    }

    /**
     * Retrieve replication status for all sessions
     *
     * @return  map of replication status per session
     */
    public Map<LogReplicationSession, ReplicationStatus> getReplicationStatus() {
        return metadataManager.getReplicationStatus();
    }

    public static ReplicationSubscriber getDefaultSubscriber() {
        return ReplicationSubscriber.newBuilder()
                .setClientName(DEFAULT_CLIENT)
                .setModel(ReplicationModel.FULL_TABLE)
                .build();
    }

    /**
     * Stop all sessions
     */
    public void stopReplication() {
        log.info("Stopping log replication.");
        stopReplication(sessions);
    }

    /**
     * Stop replication for a set of sessions and clear in-memory info
     * @param sessions
     */
    private void stopReplication(Set<LogReplicationSession> sessions) {
        if (sessions.isEmpty()) {
            return;
        }
        if (replicationManager != null) {
            // stop replication fsm and source router for outgoing sessions
            replicationManager.stop(sessions.stream().filter(outgoingSessions::contains).collect(Collectors.toSet()));
            router.stop(sessions);
        }

        sessions.forEach(session -> {
            this.sessions.remove(session);
            if (incomingSessions.contains(session)) {
                incomingMsgHandler.stopSinkManagerForSession(session);
            }
            this.outgoingSessions.remove(session);
            this.incomingSessions.remove(session);
        });

    }

    /**
     * Connect to remote cluster for sessions where the local cluster is the connection initiator.
     * If local cluster is SOURCE for any session: create FSMs and initiate connection to remote sinks
     * If local cluster is SINK for any session: initiate connection to remote sources.
     */
    public synchronized void connectToRemoteClusters() {
        if (topology.getRemoteClusterEndpoints().isEmpty()) {
            return;
        }

        router.createTransportClientAdapter(configManager.getServerContext().getPluginConfigFilePath());

        newSessionsDiscovered.stream()
                .filter(session -> topology.getRemoteClusterEndpoints().containsKey(session.getSourceClusterId()) ||
                        topology.getRemoteClusterEndpoints().containsKey(session.getSinkClusterId()))
                .forEach(session -> {
                    if (outgoingSessions.contains(session)) {
                        // initiate connection to remote Sink cluster
                        router.connect(topology.getAllClustersInTopology().get(session.getSinkClusterId()), session);
                    } else {
                        // initiate connection to remote Source cluster
                        router.connect(topology.getAllClustersInTopology().get(session.getSourceClusterId()), session);
                    }

                });
    }

    /**
     * Create runtimeFSM for sessions for which the local cluster is a SOURCE
     */
    private void createSourceFSMs() {
        newSessionsDiscovered.stream()
                .filter(outgoingSessions::contains)
                .forEach(session -> replicationManager.createAndStartRuntime(
                        topology.getAllClustersInTopology().get(session.getSinkClusterId()), session, router));
    }

    /**
     * Shutdown session manager
     */
    public void shutdown() {
        replicationManager.stop();
        router.stop(sessions);
        router.shutDownMsgHandlerServer();
    }

    /**
     * Force snapshot sync on all outgoing sessions
     *
     * @param event
     */
    public void enforceSnapshotSync(DiscoveryServiceEvent event) {
        replicationManager.enforceSnapshotSync(event);
    }

    /**
     * Check if the local cluster is a connection receiver for any of the discovered sessions
     *
     * @return true if the local cluster is a connection receiver for any session
     */
    public boolean isConnectionReceiver() {
        Set<String> connectionEndpoints = topology.getRemoteClusterEndpoints().keySet();
        for(LogReplicationSession session : sessions) {
            if (connectionEndpoints.contains(session.getSourceClusterId()) ||
                    connectionEndpoints.contains(session.getSinkClusterId())) {
                continue;
            } else {
                // there is at least 1 session where the remote cluster will initiate the connection to the local cluster
                return true;
            }
        }
        return false;
    }

    /**
     * Notify LogReplicationServer about the leadership change so that sink managers can be reset/stopped accordingly.
     */
    public void notifyLeadershipChange() {
        incomingMsgHandler.leadershipChanged();
    }
}

