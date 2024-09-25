/*
 * Copyright (c) 2023. The BifroMQ Authors. All Rights Reserved.
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

package com.baidu.bifromq.dist.server;

import static com.baidu.bifromq.plugin.eventcollector.ThreadLocalEventPool.getLocal;
import static com.baidu.bifromq.plugin.eventcollector.distservice.DistError.DistErrorCode.DROP_EXCEED_LIMIT;
import static com.baidu.bifromq.plugin.eventcollector.distservice.DistError.DistErrorCode.RPC_FAILURE;

import com.baidu.bifromq.baseenv.MemUsage;
import com.baidu.bifromq.baserpc.ResponsePipeline;
import com.baidu.bifromq.basescheduler.exception.BackPressureException;
import com.baidu.bifromq.dist.rpc.proto.DistReply;
import com.baidu.bifromq.dist.rpc.proto.DistRequest;
import com.baidu.bifromq.dist.server.scheduler.DistServerCall;
import com.baidu.bifromq.dist.server.scheduler.IDistWorkerCallScheduler;
import com.baidu.bifromq.plugin.eventcollector.IEventCollector;
import com.baidu.bifromq.plugin.eventcollector.distservice.DistError;
import com.baidu.bifromq.plugin.eventcollector.distservice.Disted;
import com.baidu.bifromq.sysprops.props.IngressSlowDownDirectMemoryUsage;
import com.baidu.bifromq.sysprops.props.IngressSlowDownHeapMemoryUsage;
import com.baidu.bifromq.sysprops.props.MaxSlowDownTimeoutSeconds;
import com.baidu.bifromq.type.PublisherMessagePack;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class DistResponsePipeline extends ResponsePipeline<DistRequest, DistReply> {
    private static final double SLOWDOWN_DIRECT_MEM_USAGE = IngressSlowDownDirectMemoryUsage.INSTANCE.get();
    private static final double SLOWDOWN_HEAP_MEM_USAGE = IngressSlowDownHeapMemoryUsage.INSTANCE.get();
    private static final Duration SLOWDOWN_TIMEOUT = Duration.ofSeconds(MaxSlowDownTimeoutSeconds.INSTANCE.get());
    private final String id = UUID.randomUUID().toString();
    private final IEventCollector eventCollector;
    private final IDistWorkerCallScheduler distCallScheduler;

    DistResponsePipeline(IDistWorkerCallScheduler distCallScheduler,
                         StreamObserver<DistReply> responseObserver,
                         IEventCollector eventCollector) {
        super(responseObserver, () -> MemUsage.local().nettyDirectMemoryUsage() > SLOWDOWN_DIRECT_MEM_USAGE
            || MemUsage.local().heapMemoryUsage() > SLOWDOWN_HEAP_MEM_USAGE, SLOWDOWN_TIMEOUT);
        this.distCallScheduler = distCallScheduler;
        this.eventCollector = eventCollector;
    }

    @Override
    protected CompletableFuture<DistReply> handleRequest(String tenantId, DistRequest request) {
        return distCallScheduler.schedule(new DistServerCall(tenantId, request.getMessagesList(), id))
            .handle((v, e) -> {
                DistReply.Builder replyBuilder = DistReply.newBuilder().setReqId(request.getReqId());
                if (e != null) {
                    if (e instanceof BackPressureException || e.getCause() instanceof BackPressureException) {
                        for (PublisherMessagePack publisherMsgPack : request.getMessagesList()) {
                            DistReply.Result.Builder resultBuilder = DistReply.Result.newBuilder();
                            for (PublisherMessagePack.TopicPack topicPack : publisherMsgPack.getMessagePackList()) {
                                resultBuilder.putTopic(topicPack.getTopic(), DistReply.Code.BACK_PRESSURE_REJECTED);
                            }
                            replyBuilder.addResults(resultBuilder.build());
                        }
                        eventCollector.report(getLocal(DistError.class)
                            .reqId(request.getReqId())
                            .messages(request.getMessagesList())
                            .code(DROP_EXCEED_LIMIT));
                    } else {
                        for (PublisherMessagePack publisherMsgPack : request.getMessagesList()) {
                            DistReply.Result.Builder resultBuilder = DistReply.Result.newBuilder();
                            for (PublisherMessagePack.TopicPack topicPack : publisherMsgPack.getMessagePackList()) {
                                resultBuilder.putTopic(topicPack.getTopic(), DistReply.Code.ERROR);
                            }
                            replyBuilder.addResults(resultBuilder.build());
                        }
                        eventCollector.report(getLocal(DistError.class)
                            .reqId(request.getReqId())
                            .messages(request.getMessagesList())
                            .code(RPC_FAILURE));
                    }
                } else {
                    int totalFanout = 0;
                    for (PublisherMessagePack publisherMsgPack : request.getMessagesList()) {
                        DistReply.Result.Builder resultBuilder = DistReply.Result.newBuilder();
                        for (PublisherMessagePack.TopicPack topicPack : publisherMsgPack.getMessagePackList()) {
                            Optional<Integer> fanout = v.get(topicPack.getTopic());
                            if (fanout.isPresent()) {
                                resultBuilder.putTopic(topicPack.getTopic(),
                                    fanout.get() > 0 ? DistReply.Code.OK : DistReply.Code.NO_MATCH);
                                totalFanout += fanout.get();
                            } else {
                                log.warn("No fanout for topic: {}", topicPack.getTopic());
                                resultBuilder.putTopic(topicPack.getTopic(), DistReply.Code.ERROR);
                            }
                        }
                        replyBuilder.addResults(resultBuilder.build());
                    }
                    eventCollector.report(getLocal(Disted.class)
                        .reqId(request.getReqId())
                        .messages(request.getMessagesList())
                        .fanout(totalFanout));
                }
                return replyBuilder.build();
            });
    }
}
