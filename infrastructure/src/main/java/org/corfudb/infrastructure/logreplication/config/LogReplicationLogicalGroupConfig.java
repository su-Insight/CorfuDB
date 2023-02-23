package org.corfudb.infrastructure.logreplication.config;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import org.corfudb.infrastructure.ServerContext;
import org.corfudb.runtime.LogReplication.LogReplicationSession;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class LogReplicationLogicalGroupConfig extends LogReplicationConfig {

    /**
     * Logical groups that are replicating by this logical group replication session.
     */
    @Getter
    @Setter
    private Map<String, Set<String>> logicalGroupToStreams;

    public LogReplicationLogicalGroupConfig(@NonNull LogReplicationSession session,
                                            @NonNull Set<String> streamsToReplicate,
                                            @NonNull Map<UUID, List<UUID>> dataStreamToTagsMap,
                                            ServerContext serverContext,
                                            @NonNull Map<String, Set<String>> logicalGroupToStreams) {
        super(session, streamsToReplicate, dataStreamToTagsMap, serverContext);
        this.logicalGroupToStreams = logicalGroupToStreams;
    }
}