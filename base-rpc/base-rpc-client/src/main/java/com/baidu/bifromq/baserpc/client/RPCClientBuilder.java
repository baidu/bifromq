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

package com.baidu.bifromq.baserpc.client;

import static com.baidu.bifromq.baserpc.utils.NettyUtil.determineSocketChannelClass;

import com.baidu.bifromq.baserpc.BluePrint;
import com.baidu.bifromq.baserpc.client.interceptor.TenantAwareClientInterceptor;
import com.baidu.bifromq.baserpc.client.loadbalancer.IServerSelector;
import com.baidu.bifromq.baserpc.client.loadbalancer.TrafficDirectiveLoadBalancerProvider;
import com.baidu.bifromq.baserpc.client.nameresolver.TrafficGovernorNameResolverProvider;
import com.baidu.bifromq.baserpc.client.util.BehaviorSubject;
import com.baidu.bifromq.baserpc.trafficgovernor.IRPCServiceLandscape;
import com.baidu.bifromq.baserpc.trafficgovernor.IRPCServiceTrafficService;
import com.google.common.base.Preconditions;
import io.grpc.Channel;
import io.grpc.ConnectivityState;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.ManagedChannel;
import io.grpc.netty.LocalInProcNettyChannelBuilder;
import io.grpc.netty.NegotiationType;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.SslContext;
import io.reactivex.rxjava3.core.Observable;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * The builder for creating RPC client.
 */
@Accessors(fluent = true)
@Setter
@NoArgsConstructor(access = AccessLevel.PACKAGE)
public final class RPCClientBuilder {
    private IRPCServiceTrafficService trafficService;
    private BluePrint bluePrint;
    private int workerThreads;
    private EventLoopGroup eventLoopGroup;
    private long keepAliveInSec;
    private long idleTimeoutInSec;
    private SslContext sslContext;

    public RPCClientBuilder sslContext(SslContext sslContext) {
        if (sslContext != null) {
            Preconditions.checkArgument(sslContext.isClient(), "Client auth must be enabled");
        }
        this.sslContext = sslContext;
        return this;
    }

    public IRPCClient build() {
        return new RPCClient(bluePrint, ClientChannel.builder()
            .bluePrint(bluePrint)
            .trafficService(trafficService)
            .eventLoopGroup(eventLoopGroup)
            .sslContext(sslContext)
            .workerThreads(workerThreads)
            .keepAliveInSec(keepAliveInSec)
            .idleTimeoutInSec(idleTimeoutInSec)
            .build());
    }
}

