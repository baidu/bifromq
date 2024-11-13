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

package com.baidu.bifromq.basekv;

import static org.awaitility.Awaitility.await;

import com.baidu.bifromq.basecluster.AgentHostOptions;
import com.baidu.bifromq.basecluster.IAgentHost;
import com.baidu.bifromq.basecrdt.service.CRDTServiceOptions;
import com.baidu.bifromq.basecrdt.service.ICRDTService;
import com.baidu.bifromq.basehlc.HLC;
import com.baidu.bifromq.basekv.proto.KVRangeStoreDescriptor;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Set;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class LandscapeCleanupTest {
    private IAgentHost agentHost1;
    private IAgentHost agentHost2;
    private ICRDTService crdtService1;
    private ICRDTService crdtService2;
    private IBaseKVMetaService metaService1;
    private IBaseKVMetaService metaService2;

    @BeforeMethod
    void setup() {
        agentHost1 = IAgentHost.newInstance(AgentHostOptions.builder().addr("127.0.0.1").build());
        agentHost1.start();
        crdtService1 = ICRDTService.newInstance(CRDTServiceOptions.builder().build());
        crdtService1.start(agentHost1);
        metaService1 = new BaseKVMetaService(crdtService1);

        agentHost2 = IAgentHost.newInstance(AgentHostOptions.builder().addr("127.0.0.1").build());
        agentHost2.start();

        agentHost1.join(Set.of(new InetSocketAddress(agentHost2.local().getAddress(), agentHost2.local().getPort())))
            .join();
        crdtService2 = ICRDTService.newInstance(CRDTServiceOptions.builder().build());
        crdtService2.start(agentHost2);
        metaService2 = new BaseKVMetaService(crdtService2);
    }

    @AfterMethod
    void tearDown() {
        metaService1.stop();
        crdtService1.stop();
        agentHost1.shutdown();
    }

    @Test
    public void testCleanup() {
        KVRangeStoreDescriptor descriptor1 = KVRangeStoreDescriptor.newBuilder()
            .setId("testStoreId1")
            .setHlc(HLC.INST.get())
            .build();
        KVRangeStoreDescriptor descriptor2 = KVRangeStoreDescriptor.newBuilder()
            .setId("testStoreId2")
            .setHlc(HLC.INST.get())
            .build();
        Map<String, KVRangeStoreDescriptor> landscape =
            Map.of(descriptor1.getId(), descriptor1, descriptor2.getId(), descriptor2);
        IBaseKVClusterMetadataManager manager1 = metaService1.metadataManager("test");
        IBaseKVClusterMetadataManager manager2 = metaService2.metadataManager("test");
        manager1.report(descriptor1).join();
        manager2.report(descriptor2).join();

        await().until(() -> landscape.equals(manager1.landscape().blockingFirst())
            && landscape.equals(manager2.landscape().blockingFirst()));

        metaService2.stop();
        crdtService2.stop();
        agentHost2.shutdown();

        Map<String, KVRangeStoreDescriptor> landscape1 = Map.of(descriptor1.getId(), descriptor1);
        await().until(() -> landscape1.equals(manager1.landscape().blockingFirst()));
    }
}