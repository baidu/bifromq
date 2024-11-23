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

package com.baidu.bifromq.inbox.store;

import com.baidu.bifromq.basecluster.IAgentHost;
import com.baidu.bifromq.basekv.client.IBaseKVStoreClient;
import com.baidu.bifromq.basekv.metaservice.IBaseKVMetaService;
import com.baidu.bifromq.basekv.store.option.KVRangeStoreOptions;
import com.baidu.bifromq.baserpc.server.RPCServerBuilder;
import com.baidu.bifromq.inbox.client.IInboxClient;
import com.baidu.bifromq.plugin.eventcollector.IEventCollector;
import com.baidu.bifromq.plugin.settingprovider.ISettingProvider;
import com.google.protobuf.Struct;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * The builder for building inbox store.
 */
@NoArgsConstructor(access = AccessLevel.PACKAGE)
@Accessors(fluent = true)
@Setter
public class InboxStoreBuilder implements IInboxStoreBuilder {
    String clusterId = IInboxStore.CLUSTER_NAME;
    RPCServerBuilder rpcServerBuilder;
    IAgentHost agentHost;
    IBaseKVMetaService metaService;
    IInboxClient inboxClient;
    IBaseKVStoreClient inboxStoreClient;
    ISettingProvider settingProvider;
    IEventCollector eventCollector;
    KVRangeStoreOptions storeOptions;
    int workerThreads;
    int tickerThreads;
    ScheduledExecutorService bgTaskExecutor;
    Duration balancerRetryDelay = Duration.ofSeconds(5);
    Map<String, Struct> balancerFactoryConfig = new HashMap<>();
    Duration loadEstimateWindow = Duration.ofSeconds(5);
    Duration gcInterval = Duration.ofMinutes(5);

    @Override
    public IInboxStore build() {
        return new InboxStore(this);
    }
}