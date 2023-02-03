package org.corfudb.infrastructure.logreplication;

import org.corfudb.infrastructure.logreplication.infrastructure.ClusterDescriptor;
import org.corfudb.infrastructure.logreplication.infrastructure.SessionManager;
import org.corfudb.infrastructure.logreplication.infrastructure.TopologyDescriptor;
import org.corfudb.infrastructure.logreplication.infrastructure.plugins.DefaultClusterConfig;
import org.corfudb.infrastructure.logreplication.infrastructure.plugins.DefaultClusterManager;
import org.corfudb.infrastructure.logreplication.utils.LogReplicationConfigManager;
import org.corfudb.runtime.CorfuRuntime;
import org.corfudb.runtime.view.AbstractViewTest;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;


public class SessionManagerTest extends AbstractViewTest {
    private CorfuRuntime corfuRuntime;
    private TopologyDescriptor topology;

    @Before
    public void setUp() {
        corfuRuntime = getDefaultRuntime();
        LogReplicationConfigManager configManager = Mockito.mock(LogReplicationConfigManager.class);
        Mockito.doReturn(corfuRuntime).when(configManager).getRuntime();
    }

    @After
    public void tearDown() {
        corfuRuntime.shutdown();
    }

    /**
     * This test verifies that the outgoing session is established using session manager.
     * It verifies that the in-memory state has captured the outgoing session.
     */
    @Test
    public void testSessionMgrWithOutgoingSession() {
        DefaultClusterManager defaultClusterManager = new DefaultClusterManager();
        DefaultClusterConfig topologyConfig = new DefaultClusterConfig();
        defaultClusterManager.setLocalNodeId(topologyConfig.getSourceNodeUuids().get(0));
        topology = defaultClusterManager.generateDefaultValidConfig();

        SessionManager sessionManager = new SessionManager(topology, corfuRuntime, defaultClusterManager.getCorfuEndpoint());
        String sourceClusterId = DefaultClusterConfig.getSourceClusterIds().get(0);
        int numSinkCluster = topology.getRemoteSinkClusters().size();

        // Verifies that the source cluster has established session with 1 sink clusters.
        Assert.assertEquals(numSinkCluster, sessionManager.getOutgoingSessions().size());
        Assert.assertEquals(sourceClusterId, topology.getLocalClusterDescriptor().getClusterId());
        Assert.assertEquals(0, sessionManager.getIncomingSessions().size());
    }

    /**
     * This test verifies that the incoming session is established using session manager.
     * It verifies that the in-memory state has captured the incoming session.
     */
    @Test
    public void testSessionMgrWithIncomingSession() {
        DefaultClusterManager defaultClusterManager = new DefaultClusterManager();
        DefaultClusterConfig topologyConfig = new DefaultClusterConfig();
        defaultClusterManager.setLocalNodeId(topologyConfig.getSinkNodeUuids().get(0));
        topology = defaultClusterManager.generateDefaultValidConfig();

        SessionManager sessionManager = new SessionManager(topology, corfuRuntime, defaultClusterManager.getCorfuEndpoint());
        String sinkClusterId = DefaultClusterConfig.getSinkClusterIds().get(0);
        int numSourceCluster = topology.getRemoteSourceClusters().size();

        // Verifies that the sink cluster has established session with all 3 source clusters.
        Assert.assertEquals(0, sessionManager.getOutgoingSessions().size());
        Assert.assertEquals(sinkClusterId, topology.getLocalClusterDescriptor().getClusterId());
        Assert.assertEquals(numSourceCluster, sessionManager.getIncomingSessions().size());
    }
}
