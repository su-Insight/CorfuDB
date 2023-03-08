package org.corfudb.infrastructure.logreplication.infrastructure.msgHandlers;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.TextFormat;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.corfudb.infrastructure.ServerContext;
import org.corfudb.infrastructure.logreplication.infrastructure.SessionManager;
import org.corfudb.infrastructure.logreplication.infrastructure.msgHandlers.LogReplicationAbstractServer;
import org.corfudb.infrastructure.logreplication.infrastructure.msgHandlers.LogReplicationMsgHandler;
import org.corfudb.infrastructure.logreplication.infrastructure.msgHandlers.ReplicationHandlerMethods;
import org.corfudb.infrastructure.logreplication.proto.LogReplicationMetadata.ReplicationMetadata;
import org.corfudb.infrastructure.logreplication.transport.IClientServerRouter;
import org.corfudb.runtime.LogReplication.LogReplicationSession;
import org.corfudb.infrastructure.logreplication.replication.receive.LogReplicationSinkManager;
import org.corfudb.runtime.LogReplication.LogReplicationMetadataResponseMsg;
import org.corfudb.runtime.LogReplication.LogReplicationEntryMsg;
import org.corfudb.runtime.LogReplication.LogReplicationEntryType;
import org.corfudb.runtime.clients.IClientRouter;
import org.corfudb.runtime.proto.service.CorfuMessage.HeaderMsg;
import org.corfudb.runtime.proto.service.CorfuMessage.RequestMsg;
import org.corfudb.runtime.proto.service.CorfuMessage.ResponseMsg;
import org.corfudb.runtime.proto.service.CorfuMessage.ResponsePayloadMsg;

import javax.annotation.Nonnull;
import java.lang.invoke.MethodHandles;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.corfudb.protocols.service.CorfuProtocolLogReplication.getLeadershipLoss;
import static org.corfudb.protocols.service.CorfuProtocolLogReplication.getLeadershipResponse;
import static org.corfudb.protocols.service.CorfuProtocolMessage.getHeaderMsg;
import static org.corfudb.protocols.service.CorfuProtocolMessage.getResponseMsg;
import static org.corfudb.protocols.CorfuProtocolCommon.getUUID;

/**
 * This class represents the Log Replication Server, which is responsible of providing Log Replication across sites.
 *
 * The Log Replication Server, handles log replication entries--which represent parts of a Snapshot (full) sync or a
 * Log Entry (delta) sync and also handles negotiation messages, which allows the Source Replicator to get a view of
 * the last synchronized point at the remote cluster.
 */
@Slf4j
public class LogReplicationServer extends LogReplicationAbstractServer {

    // Unique and immutable identifier of a server node (UUID)
    // Note: serverContext.getLocalEndpoint() can return an IP or FQDN, which is mutable (for this we
    // should have a unique way the identify a node in the  topology
    private String localNodeId;

    // Cluster Id of the local node.
    private String localClusterId;

    private final ExecutorService executor;

    private static final String EXECUTOR_NAME_PREFIX = "LogReplicationServer-";

    private Map<LogReplicationSession, LogReplicationSinkManager> sessionToSinkManagerMap = new HashMap<>();

    private final AtomicBoolean isLeader = new AtomicBoolean(false);

    @Getter
    private SessionManager sessionManager;

    private ServerContext serverContext;

    private String localEndpoint;

    /**
     * RequestHandlerMethods for the LogReplication server
     */
    @Getter
    private final ReplicationHandlerMethods handlerMethods = createHandlerMethods();

    protected ReplicationHandlerMethods createHandlerMethods() {
        return ReplicationHandlerMethods.generateHandler(MethodHandles.lookup(), this);
    }

    public LogReplicationServer(@Nonnull ServerContext context, @Nonnull SessionManager sessionManager,
                                String localEndpoint) {
        this.serverContext = context;
        this.localEndpoint = localEndpoint;
        this.localNodeId = sessionManager.getTopology().getLocalNodeDescriptor().getNodeId();
        this.localClusterId = sessionManager.getTopology().getLocalClusterDescriptor().getClusterId();
        this.sessionManager = sessionManager;
        createSinkManagers();
        this.executor = context.getExecutorService(1, EXECUTOR_NAME_PREFIX);
    }

    @VisibleForTesting
    public LogReplicationServer(@Nonnull ServerContext context, LogReplicationSinkManager sinkManager,
        String localNodeId, String localClusterId, SessionManager sessionManager) {
        this.serverContext = context;
        this.localEndpoint = null;
        sessionToSinkManagerMap.put(sinkManager.getSession(), sinkManager);
        this.localNodeId = localNodeId;
        this.localClusterId = localClusterId;
        this.sessionManager = sessionManager;
        this.executor = context.getExecutorService(1, EXECUTOR_NAME_PREFIX);
    }

     private void createSinkManagers() {
        for (LogReplicationSession session : sessionManager.getIncomingSessions()) {
            createSinkManager(session);
        }
    }

    private LogReplicationSinkManager createSinkManager(LogReplicationSession session) {
        LogReplicationSinkManager sinkManager = new LogReplicationSinkManager(localEndpoint,
                sessionManager.getMetadataManager(), serverContext, session, sessionManager.getReplicationContext());
        sessionToSinkManagerMap.put(session, sinkManager);
        log.info("Sink Manager created for session={}", session);
        return sinkManager;
    }

    public void updateTopologyConfigId(long topologyConfigId) {
        sessionToSinkManagerMap.values().forEach(sinkManager -> sinkManager.updateTopologyConfigId(topologyConfigId));
    }

    /* ************ Override Methods ************ */

    @Override
    protected void processRequest(RequestMsg req, ResponseMsg res, IClientServerRouter r) {
        executor.submit(() -> getHandlerMethods().handle(req, res, r));
    }

    @Override
    public void shutdown() {
        super.shutdown();
        executor.shutdown();
        sessionToSinkManagerMap.values().forEach(sinkManager -> sinkManager.shutdown());
        sessionToSinkManagerMap.clear();
    }

    /* ************ Server Handlers ************ */

    /**
     * Given a log-entry request message, send back an acknowledgement
     * after processing the message.
     *
     * @param request leadership query
     * @param router  router used for sending back the response
     */
    @LogReplicationMsgHandler(type = "lr_entry")
    private void handleLrEntryRequest(@Nonnull RequestMsg request,
                                      @Nonnull IClientServerRouter router) {
        log.trace("Log Replication Entry received by Server.");

        if (isLeader.get()) {
            LogReplicationSession session = getSession(request);

            LogReplicationSinkManager sinkManager = sessionToSinkManagerMap.get(session);

            // We create a sinkManager for sessions that are discovered while bootstrapping LR. But as topology changes,
            // we may discover new sessions. At the same time, its possible that the remote Source cluster finds a new
            // session before the local cluster and sends a request to the local cluster.
            // Since the two events are async, we wait to receive a new session in the incoming request.
            // If the incoming session is not known to sessionManager drop the message (until session is discovered by
            // local cluster), otherwise create a corresponding sinkManager.
            // TODO[V2] : We still have a case where the cluster does not ever discover a session on its own, like in
            //  the logical_group use case where only the source knows about the session even when sink starts the
            //  connection.
            //  To resolve this, we need to have a long living RPC from the connectionInitiator cluster which will query
            //  for sessions from the other cluster
            if (sinkManager == null) {
                if(!sessionManager.getSessions().contains(session)) {
                    log.error("SessionManager does not know about incoming session {}, total={}, current sessions={}",
                            session, sessionToSinkManagerMap.size(), sessionToSinkManagerMap.keySet());
                    return;
                } else {
                    sinkManager = createSinkManager(session);
                }
            }

            // Forward the received message to the Sink Manager for apply
            LogReplicationEntryMsg ack = sinkManager.receive(request.getPayload().getLrEntry());

            if (ack != null) {
                long ts = ack.getMetadata().getEntryType().equals(LogReplicationEntryType.LOG_ENTRY_REPLICATED) ?
                    ack.getMetadata().getTimestamp() : ack.getMetadata().getSnapshotTimestamp();
                log.info("Sending ACK {} on {} to Client ", TextFormat.shortDebugString(ack.getMetadata()), ts);

                ResponsePayloadMsg payload = ResponsePayloadMsg.newBuilder().setLrEntryAck(ack).build();
                HeaderMsg responseHeader = getHeaderMsg(request.getHeader());
                ResponseMsg response = getResponseMsg(responseHeader, payload);
                router.sendResponse(response);
            }
        } else {
            LogReplicationEntryMsg entryMsg = request.getPayload().getLrEntry();
            LogReplicationEntryType entryType = entryMsg.getMetadata().getEntryType();
            log.warn("Dropping received message of type {} while NOT LEADER. snapshotSyncSeqNumber={}, ts={}," +
                "syncRequestId={}", entryType, entryMsg.getMetadata().getSnapshotSyncSeqNum(),
                entryMsg.getMetadata().getTimestamp(), entryMsg.getMetadata().getSyncRequestId());
            sendLeadershipLoss(request, router);
        }
    }

    /**
     * Given a metadata request message, send back a response signaling
     * current log-replication status (snapshot related information).
     *
     * @param request leadership query
     * @param router  router used for sending back the response
     */
    @LogReplicationMsgHandler(type = "lr_metadata_request")
    private void handleMetadataRequest(@Nonnull RequestMsg request,
                                       @Nonnull IClientServerRouter router) {
        log.info("Log Replication Metadata Request received by Server.");

        if (isLeader.get()) {

            LogReplicationSession session = getSession(request);

            LogReplicationSinkManager sinkManager = sessionToSinkManagerMap.get(session);

            // We create a sinkManager for sessions that are discovered while bootstrapping LR. But as topology changes,
            // we may discover new sessions. At the same time, its possible that the remote Source cluster finds a new
            // session before the local cluster and sends a request to the local cluster.
            // Since the two events are async, we wait to receive a new session in the incoming request.
            // If the incoming session is not known to sessionManager drop the message (until session is discovered by
            // local cluster), otherwise create a corresponding sinkManager.
            // TODO[V2] : We still have a case where the cluster does not ever discover a session on its own.
            //  To resolve this, we need to have a long living RPC from the connectionInitiator cluster which will query
            //  for sessions from the other cluster
            if (sinkManager == null) {
                if(!sessionManager.getSessions().contains(session)) {
                    log.error("SessionManager does not know about incoming session {}, total={}, current sessions={}",
                            session, sessionToSinkManagerMap.size(), sessionToSinkManagerMap.keySet());
                    return;
                } else {
                    sinkManager = createSinkManager(session);
                }
            }

            ReplicationMetadata metadata = sessionManager.getMetadataManager().getReplicationMetadata(session);
            ResponseMsg response = getMetadataResponse(request, metadata);

            log.info("Send Metadata response: :: {}", TextFormat.shortDebugString(response.getPayload()));
            router.sendResponse(response);

            // If a snapshot apply is pending, start (if not started already)
            sinkManager.startPendingSnapshotApply();
        } else {
            log.warn("Dropping metadata request as this node is not the leader.  Request id = {}",
                request.getHeader().getRequestId());
            sendLeadershipLoss(request, router);
        }
    }

    /**
     * Get session associated to the received request.
     *
     * @param request
     * @return the session for the given request
     */
    private LogReplicationSession getSession(RequestMsg request) {
        LogReplicationSession session;

        if(!request.getHeader().hasSession()) {
            // Backward compatibility where 'session' field not present
            session = LogReplicationSession.newBuilder()
                    .setSourceClusterId(getUUID(request.getHeader().getClusterId()).toString())
                    .setSinkClusterId(localClusterId)
                    .setSubscriber(SessionManager.getDefaultSubscriber())
                    .build();
        } else {
            session = request.getHeader().getSession();
        }

        return session;
    }

    private ResponseMsg getMetadataResponse(RequestMsg request, ReplicationMetadata metadata) {

        LogReplicationMetadataResponseMsg metadataMsg = LogReplicationMetadataResponseMsg.newBuilder()
                .setTopologyConfigID(metadata.getTopologyConfigId())
                .setVersion(metadata.getVersion())
                .setSnapshotStart(metadata.getLastSnapshotStarted())
                .setSnapshotTransferred(metadata.getLastSnapshotTransferred())
                .setSnapshotApplied(metadata.getLastSnapshotApplied())
                .setLastLogEntryTimestamp(metadata.getLastLogEntryBatchProcessed())
                .build();
        ResponsePayloadMsg payload = ResponsePayloadMsg.newBuilder()
                .setLrMetadataResponse(metadataMsg)
                .build();

        return getResponseMsg(getHeaderMsg(request.getHeader()), payload);
    }

    /**
     * Given a leadership request message, send back a
     * response indicating our current leadership status.
     *
     * @param request the leadership request message
     * @param router  router used for sending back the response
     */
    @LogReplicationMsgHandler(type = "lr_leadership_query")
    private void handleLogReplicationQueryLeadership(@Nonnull RequestMsg request,
                                                     @Nonnull IClientServerRouter router) {
        log.debug("Log Replication Query Leadership Request received by Server.");
        HeaderMsg responseHeader = getHeaderMsg(request.getHeader());
        ResponseMsg response = getLeadershipResponse(responseHeader, isLeader.get(), localNodeId);
        router.sendResponse(response);
    }

    /**
     * Handle an ACK from Log Replication server.
     *
     * @param response The ack message
     * @param router   A reference to the router
     */
    @LogReplicationMsgHandler(type = "lr_leadership_loss")
    private static Object handleLogReplicationAck(@Nonnull ResponseMsg response,
                                                  @Nonnull IClientServerRouter router) {
        log.debug("Handle log replication ACK {}", response);
        return response.getPayload().getLrEntryAck();
    }

    @LogReplicationMsgHandler(type = "lr_metadata_response")
    private static Object handleLogReplicationMetadata(@Nonnull ResponseMsg response,
                                                       @Nonnull IClientServerRouter router) {
        log.debug("Handle log replication Metadata Response");
        return response.getPayload().getLrMetadataResponse();
    }

    @LogReplicationMsgHandler(type = "lr_leadership_response")
    private static Object handleLogReplicationQueryLeadershipResponse(@Nonnull ResponseMsg response,
                                                                      @Nonnull IClientServerRouter router) {
        log.trace("Handle log replication query leadership response msg {}", TextFormat.shortDebugString(response));
        return response.getPayload().getLrLeadershipResponse();
    }

    @LogReplicationMsgHandler(type = "lr_leadership_loss")
    private static Object handleLogReplicationLeadershipLoss(@Nonnull ResponseMsg response,
                                                             @Nonnull IClientServerRouter router) {
        log.debug("Handle log replication leadership loss msg {}", TextFormat.shortDebugString(response));
        return response.getPayload().getLrLeadershipLoss();
    }

    /**
     * Send a leadership loss response.  This will re-trigger leadership discovery on the Source.
     *
     * @param request   the incoming request message
     * @param router    the client router for sending the NACK
     */
    private void sendLeadershipLoss(@Nonnull RequestMsg request, @Nonnull IClientServerRouter router) {
        HeaderMsg responseHeader = getHeaderMsg(request.getHeader());
        ResponseMsg response = getLeadershipLoss(responseHeader, localNodeId);
        router.sendResponse(response);
    }

    public void setLeadership(boolean leader) {
        // Leadership change can come asynchronously from sinkClientRouter when SINK is the connection starter and the router is
        // shutting down, and also from discoveryService when connection receiver and is no longer the leader.
        // Ignore redundant updates.
        if(leader == isLeader.get()) {
            return;
        }

        isLeader.set(leader);

        if (isLeader.get()) {
            // Reset the Sink Managers on acquiring leadership
            sessionToSinkManagerMap.values().forEach(sinkManager -> sinkManager.reset());
        } else {
            // Stop the Sink Managers if leadership is lost
            sessionToSinkManagerMap.values().forEach(sinkManager -> sinkManager.stopOnLeadershipLoss());
        }
    }
}