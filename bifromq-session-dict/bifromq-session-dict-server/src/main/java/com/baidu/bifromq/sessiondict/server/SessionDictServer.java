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

package com.baidu.bifromq.sessiondict.server;

import com.baidu.bifromq.baseenv.EnvProvider;
import com.baidu.bifromq.sessiondict.RPCBluePrint;
import com.google.common.util.concurrent.MoreExecutors;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class SessionDictServer implements ISessionDictServer {
    private final SessionDictService service;
    private final ExecutorService rpcExecutor;


    SessionDictServer(SessionDictServerBuilder builder) {
        service = new SessionDictService(builder.mqttBrokerClient);
        if (builder.workerThreads == 0) {
            rpcExecutor = MoreExecutors.newDirectExecutorService();
        } else {
            rpcExecutor = ExecutorServiceMetrics.monitor(Metrics.globalRegistry,
                new ThreadPoolExecutor(builder.workerThreads,
                    builder.workerThreads, 0L,
                    TimeUnit.MILLISECONDS, new LinkedTransferQueue<>(),
                    EnvProvider.INSTANCE.newThreadFactory("dist-server-executor")), "dist-server-executor");
        }
        builder.rpcServerBuilder.bindService(service.bindService(),
            RPCBluePrint.INSTANCE,
            builder.attrs,
            builder.defaultGroupTags,
            rpcExecutor);
    }

    @Override
    public void close() {
        log.debug("Closing session dict service");
        MoreExecutors.shutdownAndAwaitTermination(rpcExecutor, 5, TimeUnit.SECONDS);
        service.close();
    }
}