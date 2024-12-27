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

package com.baidu.bifromq.mqtt;

import com.baidu.bifromq.mqtt.service.ILocalSessionServer;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class NonStandaloneMQTTBroker extends AbstractMQTTBroker<NonStandaloneMQTTBrokerBuilder> {

    private final ILocalSessionServer sessionServer;

    public NonStandaloneMQTTBroker(NonStandaloneMQTTBrokerBuilder builder) {
        super(builder);
        sessionServer = ILocalSessionServer.nonStandaloneBuilder()
            .rpcServerBuilder(builder.rpcServerBuilder)
            .sessionRegistry(builder.sessionRegistry)
            .distService(builder.distService)
            .build();
    }

    @Override
    protected void beforeBrokerStart() {
        sessionServer.start();
    }

    @Override
    protected void afterBrokerStop() {
        sessionServer.shutdown();
    }
}