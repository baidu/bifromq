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

package com.baidu.bifromq.baserpc;

import com.baidu.bifromq.baserpc.exception.RequestRejectedException;
import com.baidu.bifromq.baserpc.metrics.IRPCMeter;
import com.baidu.bifromq.baserpc.metrics.RPCMetric;
import io.grpc.CallOptions;
import io.grpc.MethodDescriptor;
import io.reactivex.rxjava3.subjects.PublishSubject;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class ManagedMessageStream<MsgT, AckT> extends ManagedBiDiStream<AckT, MsgT>
    implements IRPCClient.IMessageStream<MsgT, AckT> {
    private final ConcurrentLinkedQueue<AckT> ackSendingBuffers = new ConcurrentLinkedQueue<>();
    private final PublishSubject<MsgT> msgSubject = PublishSubject.create();
    private final PublishSubject<Long> retargetSubject = PublishSubject.create();
    private final IRPCMeter.IRPCMethodMeter meter;
    private final AtomicBoolean sending = new AtomicBoolean(false);
    private final AtomicBoolean isClosed = new AtomicBoolean(false);

    ManagedMessageStream(String tenantId,
                         @Nullable String wchKey,
                         @Nullable String targetServerId,
                         Supplier<Map<String, String>> metadataSupplier,
                         RPCClient.ChannelHolder channelHolder,
                         CallOptions callOptions,
                         MethodDescriptor<AckT, MsgT> methodDescriptor,
                         BluePrint bluePrint,
                         IRPCMeter.IRPCMethodMeter meter) {
        super(tenantId,
            wchKey,
            targetServerId,
            bluePrint.semantic(methodDescriptor.getFullMethodName()).mode(),
            metadataSupplier,
            channelHolder.channel(),
            callOptions,
            bluePrint.methodDesc(methodDescriptor.getFullMethodName()));
        this.meter = meter;
        start(channelHolder.serverSelectorObservable());
    }

    @Override
    public boolean isClosed() {
        return isClosed.get();
    }

    @Override
    public void ack(AckT ack) {
        if (isClosed.get()) {
            // pipeline has already closed, finish it with close reason
            throw new RequestRejectedException("Stream has closed");
        }
        switch (state()) {
            case Init, Normal, PendingRetarget, Retargeting -> {
                log.trace("MsgStream@{} enqueue ack: {}", hashCode(), ack);
                ackSendingBuffers.offer(ack);
                // check if pipeline is still open
                sendUntilStreamNotReadyOrNoTask();
                meter.recordCount(RPCMetric.StreamAckAcceptCount);
            }
            case StreamDisconnect, NoServerAvailable -> {
                log.trace("MsgStream@{} drop ack due to no server available: {}", hashCode(), ack);
                meter.recordCount(RPCMetric.StreamAckDropCount);
            }
            default -> {
                // do nothing
            }
        }
    }

    @Override
    public void onMessage(Consumer<MsgT> consumer) {
        msgSubject.subscribe(consumer::accept);
    }

    @Override
    public void onRetarget(Consumer<Long> consumer) {
        retargetSubject.subscribe(consumer::accept);
    }

    @Override
    public void close() {
        if (isClosed.compareAndSet(false, true)) {
            super.close();
            ackSendingBuffers.clear();
            msgSubject.onComplete();
            retargetSubject.onComplete();
        }
    }

    @Override
    boolean prepareRetarget() {
        return true;
    }

    @Override
    boolean canStartRetarget() {
        return true;
    }

    @Override
    void onStreamCreated() {
        meter.recordCount(RPCMetric.StreamCreateCount);
        retargetSubject.onNext(System.nanoTime());
    }

    @Override
    void onStreamReady() {
        sendUntilStreamNotReadyOrNoTask();
    }

    @Override
    void onStreamError(Throwable e) {
        meter.recordCount(RPCMetric.StreamErrorCount);
        ackSendingBuffers.clear();
    }

    @Override
    void onNoServerAvailable() {
        // do nothing for now
    }

    @Override
    void onReceive(MsgT out) {
        msgSubject.onNext(out);
        meter.recordCount(RPCMetric.StreamMsgReceiveCount);
    }

    private void sendUntilStreamNotReadyOrNoTask() {
        if (sending.compareAndSet(false, true)) {
            while (isReady() && !ackSendingBuffers.isEmpty()) {
                AckT ack = ackSendingBuffers.poll();
                send(ack);
                meter.recordCount(RPCMetric.StreamAckSendCount);
            }
            sending.set(false);
            if (isReady() && !ackSendingBuffers.isEmpty()) {
                // deal with the spurious notification
                sendUntilStreamNotReadyOrNoTask();
            }
        }
    }
}
