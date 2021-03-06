/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.action.admin.indices.close;

import java.io.IOException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.flush.FlushRequest;
import org.elasticsearch.action.support.replication.ReplicationOperation.Replicas;
import org.elasticsearch.action.support.replication.ReplicationRequest;
import org.elasticsearch.action.support.replication.ReplicationResponse;
import org.elasticsearch.action.support.replication.TransportReplicationAction;
import org.elasticsearch.cluster.action.shard.ShardStateAction;
import org.elasticsearch.cluster.block.ClusterBlock;
import org.elasticsearch.cluster.block.ClusterBlocks;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.lease.Releasable;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

public class TransportVerifyShardBeforeCloseAction extends TransportReplicationAction<
    TransportVerifyShardBeforeCloseAction.ShardRequest, TransportVerifyShardBeforeCloseAction.ShardRequest, ReplicationResponse> {

    public static final String NAME = "indices:admin/close" + "[s]";
    private static final Logger LOGGER = LogManager.getLogger(TransportVerifyShardBeforeCloseAction.class);

    @Inject
    public TransportVerifyShardBeforeCloseAction(final Settings settings,
                                                 final TransportService transportService,
                                                 final ClusterService clusterService,
                                                 final IndicesService indicesService,
                                                 final ThreadPool threadPool,
                                                 final ShardStateAction stateAction,
                                                 final IndexNameExpressionResolver resolver) {
        super(
            NAME,
            transportService,
            clusterService,
            indicesService,
            threadPool,
            stateAction,
            resolver,
            ShardRequest::new,
            ShardRequest::new,
            ThreadPool.Names.MANAGEMENT
        );
    }

    @Override
    protected ReplicationResponse newResponseInstance(StreamInput in) throws IOException {
        return new ReplicationResponse(in);
    }

    @Override
    protected void acquirePrimaryOperationPermit(final IndexShard primary,
                                                 final ShardRequest request,
                                                 final ActionListener<Releasable> onAcquired) {
        primary.acquireAllPrimaryOperationsPermits(onAcquired, request.timeout());
    }

    @Override
    protected void acquireReplicaOperationPermit(final IndexShard replica,
                                                 final ShardRequest request,
                                                 final ActionListener<Releasable> onAcquired,
                                                 final long primaryTerm,
                                                 final long globalCheckpoint,
                                                 final long maxSeqNoOfUpdateOrDeletes) {
        replica.acquireAllReplicaOperationsPermits(primaryTerm, globalCheckpoint, maxSeqNoOfUpdateOrDeletes, onAcquired, request.timeout());
    }

    @Override
    protected void shardOperationOnPrimary(final ShardRequest shardRequest, final IndexShard primary,
            ActionListener<PrimaryResult<ShardRequest, ReplicationResponse>> listener) {
        ActionListener.completeWith(listener, () -> {
            executeShardOperation(shardRequest, primary);
            return new PrimaryResult<>(shardRequest, new ReplicationResponse());
        });
    }

    @Override
    protected ReplicaResult shardOperationOnReplica(final ShardRequest shardRequest, final IndexShard replica) throws IOException {
        executeShardOperation(shardRequest, replica);
        return new ReplicaResult();
    }

    private void executeShardOperation(final ShardRequest request, final IndexShard indexShard) throws IOException {
        final ShardId shardId = indexShard.shardId();
        if (indexShard.getActiveOperationsCount() != IndexShard.OPERATIONS_BLOCKED) {
            throw new IllegalStateException("Index shard " + shardId + " is not blocking all operations during closing");
        }

        final ClusterBlocks clusterBlocks = clusterService.state().blocks();
        if (clusterBlocks.hasIndexBlock(shardId.getIndexName(), request.clusterBlock()) == false) {
            throw new IllegalStateException("Index shard " + shardId + " must be blocked by " + request.clusterBlock() + " before closing");
        }

        indexShard.verifyShardBeforeIndexClosing();
        indexShard.flush(new FlushRequest().force(true));
        LOGGER.debug("{} shard is ready for closing", shardId);
    }

    @Override
    protected Replicas<ShardRequest> newReplicasProxy(long primaryTerm) {
        return new VerifyShardBeforeCloseActionReplicasProxy(primaryTerm);
    }

    /**
     * A {@link ReplicasProxy} that marks as stale the shards that are unavailable during the verification
     * and the flush of the shard. This is done to ensure that such shards won't be later promoted as primary
     * or reopened in an unverified state with potential non flushed translog operations.
     */
    class VerifyShardBeforeCloseActionReplicasProxy extends ReplicasProxy {

        public VerifyShardBeforeCloseActionReplicasProxy(long primaryTerm) {
            super(primaryTerm);
        }

        @Override
        public void markShardCopyAsStaleIfNeeded(ShardId shardId, String allocationId, ActionListener<Void> listener) {
            shardStateAction.remoteShardFailed(shardId, allocationId, primaryTerm, true, "mark copy as stale", null, listener);
        }
    }

    public static class ShardRequest extends ReplicationRequest<ShardRequest> {

        private final ClusterBlock clusterBlock;

        ShardRequest(StreamInput in) throws IOException {
            super(in);
            this.clusterBlock = new ClusterBlock(in);
        }

        public ShardRequest(ShardId shardId, ClusterBlock clusterBlock) {
            super(shardId);
            this.clusterBlock = clusterBlock;
        }

        public ClusterBlock clusterBlock() {
            return clusterBlock;
        }

        @Override
        public String toString() {
            return "verify shard " + shardId + " before close with block " + clusterBlock;
        }

        @Override
        public void writeTo(final StreamOutput out) throws IOException {
            super.writeTo(out);
            clusterBlock.writeTo(out);
        }
    }
}
