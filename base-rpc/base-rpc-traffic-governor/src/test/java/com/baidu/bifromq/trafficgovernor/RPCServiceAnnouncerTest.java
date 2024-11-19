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

package com.baidu.bifromq.trafficgovernor;

import com.baidu.bifromq.basecluster.AgentHostOptions;
import com.baidu.bifromq.basecluster.IAgentHost;
import com.baidu.bifromq.basecrdt.service.CRDTServiceOptions;
import com.baidu.bifromq.basecrdt.service.ICRDTService;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;

@Slf4j
abstract class RPCServiceAnnouncerTest {
    protected IAgentHost governorAgentHost;
    protected IAgentHost clientAgentHost;
    protected IAgentHost serverAgentHost;

    @BeforeClass(alwaysRun = true)
    public void setup() {
        governorAgentHost = IAgentHost.newInstance(AgentHostOptions.builder()
            .addr("127.0.0.1")
            .baseProbeInterval(Duration.ofSeconds(10))
            .joinRetryInSec(5)
            .joinTimeout(Duration.ofMinutes(5))
            .build());
        clientAgentHost = IAgentHost.newInstance(AgentHostOptions.builder()
            .addr("127.0.0.1")
            .baseProbeInterval(Duration.ofSeconds(10))
            .joinRetryInSec(5)
            .joinTimeout(Duration.ofMinutes(5))
            .build());
        log.info("Agent host started");

        serverAgentHost = IAgentHost.newInstance(AgentHostOptions.builder()
            .addr("127.0.0.1")
            .baseProbeInterval(Duration.ofSeconds(10))
            .joinRetryInSec(5)
            .joinTimeout(Duration.ofMinutes(5))
            .build());
        log.info("Agent host started");
        governorAgentHost.join(
            Set.of(new InetSocketAddress(clientAgentHost.local().getAddress(), clientAgentHost.local().getPort())));
        clientAgentHost.join(
            Set.of(new InetSocketAddress(serverAgentHost.local().getAddress(), serverAgentHost.local().getPort())));
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() {
        governorAgentHost.close();
        clientAgentHost.close();
        serverAgentHost.close();
    }

    protected ICRDTService newCRDTService(IAgentHost agentHost) {
        ICRDTService crdtService = ICRDTService.newInstance(agentHost, CRDTServiceOptions.builder().build());
        log.info("CRDT service started");
        return crdtService;
    }
}
