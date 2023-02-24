package org.corfudb.infrastructure.logreplication.infrastructure;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Sets;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.corfudb.infrastructure.AbstractServer;
import org.corfudb.infrastructure.ServerContext;
import org.corfudb.infrastructure.logreplication.replication.receive.LogReplicationMetadataManager;
import org.corfudb.infrastructure.logreplication.proto.LogReplicationMetadata.ReplicationMetadata;
import org.corfudb.infrastructure.logreplication.proto.LogReplicationMetadata.ReplicationStatus;
import org.corfudb.infrastructure.logreplication.runtime.LogReplicationSinkServerRouter;
import org.corfudb.infrastructure.logreplication.runtime.LogReplicationSourceServerRouter;
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

/**
 * Manage log replication sessions for multiple replication models.
 *
 * A replication session is determined by the cluster endpoints (source & sink)--given by the
 * Cluster Manager (topology)--the replication model and the client (given by clients upon
 * explicit registration or implicitly through protoBuf schema options).
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
    private final Set<LogReplicationSession> sessions = new HashSet<>();

    private final Set<LogReplicationSession> incomingSessions = new HashSet<>();

    private final Set<LogReplicationSession> outgoingSessions = new HashSet<>();

    private final Set<LogReplicationSession> newSessionsDiscovered = new HashSet<>();

    @Getter
    private TopologyDescriptor topology;

    @Getter
    private final LogReplicationMetadataManager metadataManager;

    private final LogReplicationUpgradeManager upgradeManager;

    @Getter
    private final LogReplicationContext replicationContext;

    private final LogReplicationRouterManager routerManager;

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

        this.metadataManager = new LogReplicationMetadataManager(corfuRuntime, topology.getTopologyConfigId());
        this.configManager = new LogReplicationConfigManager(runtime, serverContext);
        this.upgradeManager = upgradeManager;
        this.replicationContext = new LogReplicationContext(configManager, topology.getTopologyConfigId(),
                localCorfuEndpoint);

        this.routerManager = new LogReplicationRouterManager(topology);
        this.replicationManager = new CorfuReplicationManager(topology, metadataManager,
                configManager.getServerContext().getPluginConfigFilePath(), runtime, upgradeManager,
                replicationContext, routerManager);
    }

    /**
     * Test constructor.
     *
     * @param topology            the current topology
     * @param corfuRuntime        runtime for database access
     */
    @VisibleForTesting
    public SessionManager(@Nonnull TopologyDescriptor topology, CorfuRuntime corfuRuntime) {
        this.topology = topology;
        this.runtime = corfuRuntime;
        this.corfuStore = new CorfuStore(corfuRuntime);

        NodeLocator lrNodeLocator = NodeLocator.builder().host("localhost")
            .port(topology.getLocalClusterDescriptor().getCorfuPort())
            .build();
        this.localCorfuEndpoint = lrNodeLocator.toEndpointUrl();
        this.metadataManager = new LogReplicationMetadataManager(corfuRuntime, topology.getTopologyConfigId());
        this.configManager = new LogReplicationConfigManager(runtime);
        this.upgradeManager = null;
        this.routerManager = new LogReplicationRouterManager(topology);
        this.replicationContext = new LogReplicationContext(configManager, topology.getTopologyConfigId(), localCorfuEndpoint);
    }

    /**
     * Refresh sessions based on new topoloogy
     *
     * @param newTopology   the new discovered topology
     */
    public synchronized void refresh(@Nonnull TopologyDescriptor newTopology) {

        Set<String> newSources = newTopology.getRemoteSourceClusters().keySet();
        Set<String> currentSources = topology.getRemoteSourceClusters().keySet();
        Set<String> sourcesToRemove = Sets.difference(currentSources, newSources);

        Set<String> newSinks = newTopology.getRemoteSinkClusters().keySet();
        Set<String> currentSinks = topology.getRemoteSinkClusters().keySet();
        Set<String> sinksToRemove = Sets.difference(currentSinks, newSinks);
        Set<String> sinkClustersUnchanged = Sets.intersection(newSinks, currentSinks);

        Set<LogReplicationSession> sessionsToRemove = new HashSet<>();
        Set<LogReplicationSession> sessionsUnchanged = new HashSet<>();

        try {
            IRetry.build(IntervalRetry.class, () -> {
                try (TxnContext txn = corfuStore.txn(LogReplicationMetadataManager.NAMESPACE)) {
                    sessions.forEach(session -> {
                        if (sinksToRemove.contains(session.getSinkClusterId()) ||
                                sourcesToRemove.contains(session.getSourceClusterId())) {
                            sessionsToRemove.add(session);
                            metadataManager.removeSession(txn, session);
                        }

                        if(sinkClustersUnchanged.contains(session.getSinkClusterId())) {
                            sessionsUnchanged.add(session);
                            metadataManager.updateReplicationMetadataField(txn, session,
                                ReplicationMetadata.TOPOLOGYCONFIGID_FIELD_NUMBER, newTopology.getTopologyConfigId());
                        }
                    });
                    txn.commit();
                } catch (TransactionAbortedException e) {
                    throw new RetryNeededException();
                }

                // Update the in-memory topology config id after metadata tables have been updated
                topology = newTopology;
                replicationContext.setTopologyConfigId(topology.getTopologyConfigId());
                metadataManager.setTopologyConfigId(topology.getTopologyConfigId());

                stopSessions(sessionsToRemove);
                updateReplicationParameters(sessionsUnchanged);
                createSessions();
                return null;
            }).run();
        } catch (InterruptedException e) {
            log.error("Unrecoverable exception while refreshing sessions", e);
            throw new UnrecoverableCorfuInterruptedError(e);
        }
    }

    private void updateReplicationParameters(Set<LogReplicationSession> sessionsUnchanged) {
        if (replicationManager == null) {
            return;
        }
        for (LogReplicationSession session : sessionsUnchanged) {
            ClusterDescriptor cluster;
            if (session.getSourceClusterId().equals(topology.getLocalClusterDescriptor().getClusterId())) {
                cluster = topology.getRemoteSinkClusters().get(session.getSourceClusterId());
            } else {
                cluster = topology.getRemoteSinkClusters().get(session.getSinkClusterId());
            }
            replicationManager.refreshRuntime(session, cluster, topology.getTopologyConfigId());
        }

        replicationManager.updateTopology(topology);
    }

    /**
     * Create sessions (outgoing and incoming) along with metadata
     *
     */
    private void createSessions() {
        Set<LogReplicationSession> sessionsToAdd = new HashSet<>();
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
                            sessionsToAdd.add(session);
                            outgoingSessionsToAdd.add(session);
                            metadataManager.addSession(txn, session, topology.getTopologyConfigId(), false);
                        }
                    }

                    for(ClusterDescriptor remoteSourceCluster : topology.getRemoteSourceClusters().values()) {
                        LogReplicationSession session = constructSession(remoteSourceCluster.getClusterId(), localClusterId);
                        if (!sessions.contains(session)) {
                            sessionsToAdd.add(session);
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

        newSessionsDiscovered.addAll(sessionsToAdd);
        sessions.addAll(sessionsToAdd);
        incomingSessions.addAll(incomingSessionsToAdd);
        outgoingSessions.addAll(outgoingSessionsToAdd);

        // TODO(V2): The below method logs a mapping of a session's hashcode to the session itself.  This
        //  is because LR names worker threads corresponding to a session using its hashcode.
        //  To identify the remote cluster and replication information while debugging, we need a view of
        //  the whole session object.  However, this method of logging the mapping has limitations
        //  because the mapping is only printed when the session is created for the first time.  If the
        //  logs have rolled over, the information is lost.  We need a permanent store/log of this mapping.
        logNewlyAddedSessionInfo(sessionsToAdd);

        log.info("Total sessions={}, outgoing={}, incoming={}, sessions={}", sessions.size(), outgoingSessions.size(),
                incomingSessions.size(), sessions);
    }


    private void logNewlyAddedSessionInfo(Set<LogReplicationSession> newlyAddedSessions) {
        log.info("========= HashCode -> Session mapping for newly added session: =========");
        for (LogReplicationSession session : newlyAddedSessions) {
            log.info("HashCode: {}, Session: {}", session.hashCode(), session);
        }
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

    public void startConnectionReceivingRouters(boolean isSource, boolean isSink, Map<Class, AbstractServer> serverMap,
                                                CorfuInterClusterReplicationServerNode interClusterServerNode) {

        Map<LogReplicationSession, LogReplicationSourceServerRouter> sessionToSourceServer = new HashMap<>();
        Map<LogReplicationSession, LogReplicationSinkServerRouter> sessionToSinkServer = new HashMap<>();

        if (isSource) {
            createSourceReceivingComponents(sessionToSourceServer, serverMap);
        }

        if(isSink) {
            createSinkReceivingComponents(sessionToSinkServer, serverMap);
        }

        // create connection receiving routers to pass to the interClusterServerNode.
        // The incoming messages will be routed to the appropriate router in the transport layer adapter.
        if(isConnectionReceiver()) {
            interClusterServerNode.setRouterAndStartServer(sessionToSourceServer, sessionToSinkServer);
        }
    }

    // create SOURCE routers and runtime if the cluster is not a connection starter
    private void createSourceReceivingComponents(Map<LogReplicationSession, LogReplicationSourceServerRouter> sessionToSourceServer,
                                                 Map<Class, AbstractServer> serverMap) {
        log.info("Starting Source (sender/replicator) components");

        // find remote sink clusters which will connect to the local cluster
        Set<String> incomingConnections = new HashSet<>(topology.getRemoteSinkClusters().keySet());
        topology.getRemoteClusterEndpoints().keySet().forEach(incomingConnections::remove);

        if (!incomingConnections.isEmpty()) {
            newSessionsDiscovered.stream()
                    .filter(session -> outgoingSessions.contains(session))
                    .filter(session -> incomingConnections.contains(session.getSinkClusterId()))
                    .forEach(session -> {
                        sessionToSourceServer.put(session,
                                (LogReplicationSourceServerRouter) replicationManager.createSourceRouter(
                                        session, serverMap, false)
                        );
                    });
        } else {
            log.debug("No remote SINK cluster expected to connect to the local cluster");
        }
    }

    // create SINK routers if the cluster is not a connection starter
    private void createSinkReceivingComponents(Map<LogReplicationSession, LogReplicationSinkServerRouter> sessionToSinkServe,
                                               Map<Class, AbstractServer> serverMap) {
        log.info("Starting Sink (receiver) components");

        // find remote source clusters which will connect to the local cluster
        Set<String> incomingConnections = new HashSet<>(topology.getRemoteSourceClusters().keySet());
        topology.getRemoteClusterEndpoints().keySet().forEach(incomingConnections::remove);

        if (!incomingConnections.isEmpty()) {
            log.debug("starting sink and wait for connection from remote sources: {}", incomingConnections);
            newSessionsDiscovered.stream()
                    .filter(session -> incomingSessions.contains(session))
                    .filter(session -> incomingConnections.contains(session.getSourceClusterId()))
                    .forEach(session -> {
                        sessionToSinkServe.put(session,
                                routerManager.getOrCreateSinkRouter(session, serverMap,false,
                                        configManager.getServerContext().getPluginConfigFilePath(),
                                        runtime.getParameters().getRequestTimeout().toMillis()));
                    });
        } else {
            log.debug("No remote SOURCE cluster expected to connect to the local cluster");
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

    public void stopSessions() {
        log.info("Stopping log replication.");
        stopSessions(sessions);
        replicationManager.stop();
    }

    /**
     * Stop replication for outgoing sessions and clear in-memory route info for incoming sessions
     * @param sessions
     */
    private void stopSessions(Set<LogReplicationSession> sessions) {
        if (replicationManager != null) {
            // stop replication fsm and source router for outgoing sessions
            replicationManager.stop(sessions);
            // clear sink router information
            sessions.stream().filter(incomingSessions::contains)
                    .forEach(session -> {
                        // stop connection starter sink routers
                        if (topology.getRemoteClusterEndpoints().containsKey(session.getSourceClusterId())) {
                            routerManager.stopSinkClientRouter(session);
                        }
                        routerManager.clearRouterInfo(session);
                    });
        }

        sessions.forEach(session -> {
            this.sessions.remove(session);
            this.outgoingSessions.remove(session);
            this.incomingSessions.remove(session);
        });

    }

    public synchronized void connectToRemoteClusters(Map<Class, AbstractServer> serverMap) {
            newSessionsDiscovered.stream()
                    .filter(session -> topology.getRemoteClusterEndpoints().containsKey(session.getSourceClusterId()) ||
                            topology.getRemoteClusterEndpoints().containsKey(session.getSinkClusterId()))
                    .forEach(session -> replicationManager.startConnection(session, serverMap, outgoingSessions.contains(session)));
    }

    /**
     * Shutdown session manager
     */
    public void shutdown() {
        if (replicationManager != null) {
            replicationManager.stop();
        }
    }

    /**
     * Force snapshot sync on all outgoing sessions
     *
     * @param event
     */
    public void enforceSnapshotSync(DiscoveryServiceEvent event) {
        replicationManager.enforceSnapshotSync(event);
    }

    public boolean isConnectionReceiver() {
        Set<String> connectionEndpoints = topology.getRemoteClusterEndpoints().keySet();
        for(LogReplicationSession session : sessions) {
            if (!connectionEndpoints.contains(session.getSinkClusterId()) &&
                    !connectionEndpoints.contains(session.getSourceClusterId())) {
                return true;
            }
        }
        return false;
    }
}