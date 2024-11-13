/*
 * Copyright (c) 2024. The BifroMQ Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package com.baidu.bifromq.dist.server.scheduler;

import static com.baidu.bifromq.basekv.client.KVRangeRouterUtil.findByKey;
import static com.baidu.bifromq.basekv.utils.BoundaryUtil.FULL_BOUNDARY;
import static com.baidu.bifromq.dist.entity.EntityUtil.matchRecordKeyPrefix;
import static com.baidu.bifromq.dist.entity.EntityUtil.tenantUpperBound;
import static com.baidu.bifromq.util.TopicConst.NUL;

import com.baidu.bifromq.basekv.client.IBaseKVStoreClient;
import com.baidu.bifromq.basekv.client.KVRangeRouterUtil;
import com.baidu.bifromq.basekv.client.KVRangeSetting;
import com.baidu.bifromq.basekv.proto.Boundary;
import com.baidu.bifromq.basekv.proto.KVRangeId;
import com.baidu.bifromq.basekv.store.proto.KVRangeRORequest;
import com.baidu.bifromq.basekv.store.proto.ROCoProcInput;
import com.baidu.bifromq.basekv.store.proto.ReplyCode;
import com.baidu.bifromq.basescheduler.IBatchCall;
import com.baidu.bifromq.basescheduler.ICallTask;
import com.baidu.bifromq.dist.entity.EntityUtil;
import com.baidu.bifromq.dist.rpc.proto.BatchDistReply;
import com.baidu.bifromq.dist.rpc.proto.BatchDistRequest;
import com.baidu.bifromq.dist.rpc.proto.DistPack;
import com.baidu.bifromq.dist.rpc.proto.DistServiceROCoProcInput;
import com.baidu.bifromq.dist.trie.TopicFilterIterator;
import com.baidu.bifromq.dist.trie.TopicTrieNode;
import com.baidu.bifromq.plugin.settingprovider.ISettingProvider;
import com.baidu.bifromq.plugin.settingprovider.Setting;
import com.baidu.bifromq.type.ClientInfo;
import com.baidu.bifromq.type.Message;
import com.baidu.bifromq.type.TopicMessagePack;
import com.baidu.bifromq.util.TopicUtil;
import com.google.common.collect.Iterables;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class BatchDistServerCall
    implements IBatchCall<DistServerCall, Map<String, Optional<Integer>>, DistServerCallBatcherKey> {
    private final IBaseKVStoreClient distWorkerClient;
    private final ISettingProvider settingProvider;
    private final DistServerCallBatcherKey batcherKey;
    private final String orderKey;
    private final Queue<ICallTask<DistServerCall, Map<String, Optional<Integer>>, DistServerCallBatcherKey>>
        tasks = new ArrayDeque<>(128);
    private Map<String, Map<ClientInfo, Iterable<Message>>> batch = new HashMap<>(128);

    BatchDistServerCall(IBaseKVStoreClient distWorkerClient, ISettingProvider settingProvider,
                        DistServerCallBatcherKey batcherKey) {
        this.distWorkerClient = distWorkerClient;
        this.settingProvider = settingProvider;
        this.batcherKey = batcherKey;
        this.orderKey = batcherKey.tenantId() + batcherKey.batcherId();
    }

    @Override
    public void reset() {
        batch = new HashMap<>(128);
    }

    @Override
    public void add(
        ICallTask<DistServerCall, Map<String, Optional<Integer>>, DistServerCallBatcherKey> callTask) {
        callTask.call().publisherMessagePacks().forEach(publisherMsgPack ->
            publisherMsgPack.getMessagePackList().forEach(topicMsgs ->
                batch.computeIfAbsent(topicMsgs.getTopic(), k -> new HashMap<>())
                    .compute(publisherMsgPack.getPublisher(), (k, v) -> {
                        if (v == null) {
                            v = topicMsgs.getMessageList();
                        } else {
                            v = Iterables.concat(v, topicMsgs.getMessageList());
                        }
                        return v;
                    })));
        tasks.add(callTask);
    }

    @Override
    public CompletableFuture<Void> execute() {
        long reqId = System.nanoTime();
        @SuppressWarnings("unchecked")
        CompletableFuture<BatchDistReply>[] distReplyFutures = replicaSelect(rangeLookup())
            .entrySet()
            .stream()
            .map(entry -> {
                KVRangeReplica rangeReplica = entry.getKey();
                Map<String, Map<ClientInfo, Iterable<Message>>> replicaBatch = entry.getValue();
                BatchDistRequest.Builder batchDistBuilder = BatchDistRequest.newBuilder()
                    .setReqId(reqId)
                    .setOrderKey(orderKey);
                replicaBatch.forEach((topic, publisherMsgs) -> {
                    String tenantId = batcherKey.tenantId();
                    DistPack.Builder distPackBuilder = DistPack.newBuilder().setTenantId(tenantId);
                    TopicMessagePack.Builder topicMsgPackBuilder =
                        TopicMessagePack.newBuilder().setTopic(topic);
                    publisherMsgs.forEach((publisher, msgs) ->
                        topicMsgPackBuilder.addMessage(TopicMessagePack.PublisherPack
                            .newBuilder()
                            .setPublisher(publisher)
                            .addAllMessage(msgs)
                            .build()));
                    distPackBuilder.addMsgPack(topicMsgPackBuilder.build());
                    batchDistBuilder.addDistPack(distPackBuilder.build());
                });
                return distWorkerClient.query(rangeReplica.storeId, KVRangeRORequest.newBuilder()
                        .setReqId(reqId)
                        .setVer(rangeReplica.ver)
                        .setKvRangeId(rangeReplica.id)
                        .setRoCoProc(ROCoProcInput.newBuilder()
                            .setDistService(DistServiceROCoProcInput.newBuilder()
                                .setBatchDist(batchDistBuilder.build())
                                .build())
                            .build())
                        .build(), orderKey)
                    .thenApply(v -> {
                        if (v.getCode() == ReplyCode.Ok) {
                            BatchDistReply batchDistReply = v.getRoCoProcResult()
                                .getDistService()
                                .getBatchDist();
                            assert batchDistReply.getReqId() == reqId;
                            return batchDistReply;
                        }
                        log.warn("Failed to exec ro co-proc[code={}]", v.getCode());
                        throw new RuntimeException("Failed to exec rw co-proc");
                    });
            })
            .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(distReplyFutures)
            .handle((v, e) -> {
                ICallTask<DistServerCall, Map<String, Optional<Integer>>, DistServerCallBatcherKey> task;
                if (e != null) {
                    while ((task = tasks.poll()) != null) {
                        task.resultPromise().completeExceptionally(e);
                    }
                } else {
                    // aggregate fanout from each reply
                    Map<String, Integer> allFanoutByTopic = new HashMap<>();
                    for (CompletableFuture<BatchDistReply> replyFuture : distReplyFutures) {
                        BatchDistReply reply = replyFuture.join();
                        for (String tenantId : reply.getResultMap().keySet()) {
                            assert tenantId.equals(batcherKey.tenantId());
                            Map<String, Integer> topicFanout =
                                reply.getResultMap().get(tenantId).getFanoutMap();
                            topicFanout.forEach((topic, fanout) ->
                                allFanoutByTopic.compute(topic, (k, val) -> {
                                    if (val == null) {
                                        val = 0;
                                    }
                                    val += fanout;
                                    return val;
                                }));

                        }
                    }
                    while ((task = tasks.poll()) != null) {
                        Map<String, Optional<Integer>> fanoutResult = new HashMap<>();
                        task.call().publisherMessagePacks().forEach(clientMessagePack ->
                            clientMessagePack.getMessagePackList().forEach(topicMessagePack ->
                                fanoutResult.put(topicMessagePack.getTopic(), Optional.ofNullable(
                                    allFanoutByTopic.get(topicMessagePack.getTopic())))));
                        task.resultPromise().complete(fanoutResult);
                    }
                }
                return null;
            });
    }

    private Map<KVRangeSetting, Map<String, Map<ClientInfo, Iterable<Message>>>> rangeLookup() {
        Map<KVRangeSetting, Map<String, Map<ClientInfo, Iterable<Message>>>> batchByRange =
            new HashMap<>();
        if (distWorkerClient.latestEffectiveRouter().containsKey(FULL_BOUNDARY)) {
            // no splitting
            assert distWorkerClient.latestEffectiveRouter().size() == 1;
            batchByRange.put(distWorkerClient.latestEffectiveRouter().get(FULL_BOUNDARY), batch);
        } else {
            List<KVRangeSetting> coveredRanges = KVRangeRouterUtil.findByBoundary(Boundary.newBuilder()
                .setStartKey(matchRecordKeyPrefix(batcherKey.tenantId()))
                .setEndKey(tenantUpperBound(batcherKey.tenantId()))
                .build(), distWorkerClient.latestEffectiveRouter());
            if (coveredRanges.size() == 1) {
                // one range per tenant mode
                batchByRange.put(coveredRanges.get(0), batch);
            } else {
                // multi ranges per tenant mode
                for (String topic : batch.keySet()) {
                    if (!(boolean) settingProvider.provide(Setting.WildcardSubscriptionEnabled,
                        batcherKey.tenantId())) {
                        // wildcard disabled
                        Optional<KVRangeSetting> rangeSetting =
                            findByKey(matchRecordKeyPrefix(batcherKey.tenantId(), topic),
                                distWorkerClient.latestEffectiveRouter());
                        assert rangeSetting.isPresent();
                        batchByRange.computeIfAbsent(rangeSetting.get(), k -> new HashMap<>())
                            .put(topic, batch.get(topic));
                    } else {
                        // wildcard disabled, rough screening via 'one-pass' scan
                        Map<ClientInfo, Iterable<Message>> publisherMsg = batch.get(topic);
                        TopicFilterIterator<String> topicFilterIterator =
                            new TopicFilterIterator<>(TopicTrieNode.<String>builder(true)
                                .addTopic(TopicUtil.parse(batcherKey.tenantId(), topic, false), topic)
                                .build());
                        for (Boundary boundary : distWorkerClient.latestEffectiveRouter().keySet()) {
                            KVRangeSetting rangeSetting =
                                distWorkerClient.latestEffectiveRouter().get(boundary);
                            if (!boundary.hasStartKey()) {
                                // left open range, must be the first range
                                EntityUtil.TenantAndEscapedTopicFilter endTenantAndEscapedTopicFilter =
                                    EntityUtil.parseTenantAndEscapedTopicFilter(boundary.getEndKey());
                                List<String> globalTopicFilter =
                                    TopicUtil.parse(endTenantAndEscapedTopicFilter.tenantId(),
                                        endTenantAndEscapedTopicFilter.escapedTopicFilter(), true);
                                topicFilterIterator.seekPrev(globalTopicFilter);
                                if (topicFilterIterator.isValid()) {
                                    batchByRange.computeIfAbsent(rangeSetting, k -> new HashMap<>())
                                        .computeIfAbsent(topic, k -> new HashMap<>())
                                        .putAll(publisherMsg);
                                }
                            } else if (!boundary.hasEndKey()) {
                                // right open range, must be the last range
                                EntityUtil.TenantAndEscapedTopicFilter startTenantAndEscapedTopicFilter =
                                    EntityUtil.parseTenantAndEscapedTopicFilter(boundary.getStartKey());
                                List<String> globalTopicFilter =
                                    TopicUtil.parse(startTenantAndEscapedTopicFilter.tenantId(),
                                        startTenantAndEscapedTopicFilter.escapedTopicFilter(), true);
                                topicFilterIterator.seek(globalTopicFilter);
                                if (topicFilterIterator.isValid()) {
                                    batchByRange.computeIfAbsent(rangeSetting, k -> new HashMap<>())
                                        .computeIfAbsent(topic, k -> new HashMap<>())
                                        .putAll(publisherMsg);
                                }
                            } else {
                                EntityUtil.TenantAndEscapedTopicFilter startTenantAndEscapedTopicFilter =
                                    EntityUtil.parseTenantAndEscapedTopicFilter(boundary.getStartKey());
                                List<String> startGlobalTopicFilter =
                                    TopicUtil.parse(startTenantAndEscapedTopicFilter.tenantId(),
                                        startTenantAndEscapedTopicFilter.escapedTopicFilter(), true);
                                topicFilterIterator.seek(startGlobalTopicFilter);
                                if (topicFilterIterator.isValid()) {
                                    String probeTopicFilter =
                                        TopicUtil.fastJoin(NUL, topicFilterIterator.key());
                                    EntityUtil.TenantAndEscapedTopicFilter endTenantAndEscapedTopicFilter =
                                        EntityUtil.parseTenantAndEscapedTopicFilter(boundary.getEndKey());
                                    String endTopicFilter =
                                        endTenantAndEscapedTopicFilter.toGlobalTopicFilter();
                                    if (probeTopicFilter.compareTo(endTopicFilter) < 0) {
                                        batchByRange.computeIfAbsent(rangeSetting, k -> new HashMap<>())
                                            .computeIfAbsent(topic, k -> new HashMap<>())
                                            .putAll(publisherMsg);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return batchByRange;
    }

    private Map<KVRangeReplica, Map<String, Map<ClientInfo, Iterable<Message>>>> replicaSelect(
        Map<KVRangeSetting, Map<String, Map<ClientInfo, Iterable<Message>>>> batchByRange) {
        Map<KVRangeReplica, Map<String, Map<ClientInfo, Iterable<Message>>>> batchByReplica = new HashMap<>();
        for (KVRangeSetting rangeSetting : batchByRange.keySet()) {
            Map<String, Map<ClientInfo, Iterable<Message>>> rangeBatch = batchByRange.get(rangeSetting);
            if (rangeSetting.hasInProcReplica() || rangeSetting.allReplicas.size() == 1) {
                // build-in or single replica
                batchByReplica.put(new KVRangeReplica(rangeSetting.id, rangeSetting.ver, rangeSetting.randomReplica()),
                    rangeBatch);
            } else {
                for (String topic : rangeBatch.keySet()) {
                    Map<ClientInfo, Iterable<Message>> rangePublisherMsgs = rangeBatch.get(topic);
                    for (ClientInfo publisher : rangePublisherMsgs.keySet()) {
                        // bind replica based on tenantId, topic
                        int hash = Objects.hash(batcherKey.tenantId(), topic);
                        int replicaIdx = Math.abs(hash) % rangeSetting.allReplicas.size();
                        // replica bind
                        batchByReplica.computeIfAbsent(new KVRangeReplica(rangeSetting.id, rangeSetting.ver,
                                rangeSetting.allReplicas.get(replicaIdx)), k -> new HashMap<>())
                            .computeIfAbsent(topic, k -> new HashMap<>())
                            .put(publisher, rangePublisherMsgs.get(publisher));
                    }
                }
            }
        }
        return batchByReplica;
    }

    private record KVRangeReplica(KVRangeId id, long ver, String storeId) {
    }
}