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

package com.baidu.bifromq.plugin.authprovider;

import static com.baidu.bifromq.plugin.eventcollector.ThreadLocalEventPool.getLocal;
import static com.baidu.bifromq.plugin.settingprovider.Setting.ByPassPermCheckError;

import com.baidu.bifromq.plugin.authprovider.type.CheckResult;
import com.baidu.bifromq.plugin.authprovider.type.Error;
import com.baidu.bifromq.plugin.authprovider.type.Failed;
import com.baidu.bifromq.plugin.authprovider.type.Granted;
import com.baidu.bifromq.plugin.authprovider.type.MQTT3AuthData;
import com.baidu.bifromq.plugin.authprovider.type.MQTT3AuthResult;
import com.baidu.bifromq.plugin.authprovider.type.MQTT5AuthData;
import com.baidu.bifromq.plugin.authprovider.type.MQTT5AuthResult;
import com.baidu.bifromq.plugin.authprovider.type.MQTT5ExtendedAuthData;
import com.baidu.bifromq.plugin.authprovider.type.MQTT5ExtendedAuthResult;
import com.baidu.bifromq.plugin.authprovider.type.MQTTAction;
import com.baidu.bifromq.plugin.authprovider.type.Reject;
import com.baidu.bifromq.plugin.eventcollector.IEventCollector;
import com.baidu.bifromq.plugin.eventcollector.mqttbroker.accessctrl.AccessControlError;
import com.baidu.bifromq.plugin.settingprovider.ISettingProvider;
import com.baidu.bifromq.type.ClientInfo;
import io.micrometer.core.instrument.Timer;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.pf4j.PluginManager;

@Slf4j
public class AuthProviderManager implements IAuthProvider {
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final IAuthProvider delegate;
    private final ISettingProvider settingProvider;
    private final IEventCollector eventCollector;
    private MetricManager metricMgr;

    public AuthProviderManager(String authProviderFQN,
                               PluginManager pluginMgr,
                               ISettingProvider settingProvider,
                               IEventCollector eventCollector) {
        this.settingProvider = settingProvider;
        this.eventCollector = eventCollector;
        Map<String, IAuthProvider> availAuthProviders = pluginMgr.getExtensions(IAuthProvider.class)
            .stream().collect(Collectors.toMap(e -> e.getClass().getName(), e -> e));
        if (availAuthProviders.isEmpty()) {
            log.warn("No auth provider plugin available, use DEV ONLY one instead");
            delegate = new DevOnlyAuthProvider();
        } else {
            if (authProviderFQN == null) {
                log.warn("Auth provider plugin type are not specified, use DEV ONLY one instead");
                delegate = new DevOnlyAuthProvider();
            } else if (!availAuthProviders.containsKey(authProviderFQN)) {
                log.warn("Auth provider plugin type '{}' not found, use DEV ONLY one instead", authProviderFQN);
                delegate = new DevOnlyAuthProvider();
            } else {
                log.info("Auth provider plugin type: {}", authProviderFQN);
                delegate = availAuthProviders.get(authProviderFQN);
            }
        }
        init();
    }

    private void init() {
        metricMgr = new MetricManager(delegate.getClass().getName());
    }

    @Override
    public CompletableFuture<MQTT3AuthResult> auth(MQTT3AuthData authData) {
        assert !stopped.get();
        Timer.Sample start = Timer.start();
        try {
            return delegate.auth(authData)
                .handle((v, e) -> {
                    if (e != null) {
                        metricMgr.authCallErrorCounter.increment();
                        return MQTT3AuthResult.newBuilder()
                            .setReject(Reject.newBuilder()
                                .setCode(Reject.Code.Error)
                                .setReason(e.getMessage() != null ? e.getMessage() : e.toString())
                                .build())
                            .build();
                    } else {
                        start.stop(metricMgr.authCallTimer);
                        return v;
                    }
                });
        } catch (Throwable e) {
            metricMgr.authCallErrorCounter.increment();
            log.warn("Unexpected error", e);
            Reject.Builder rb = Reject.newBuilder().setCode(Reject.Code.Error);
            if (e.getMessage() != null) {
                rb.setReason(e.getMessage());
            }
            return CompletableFuture.completedFuture(MQTT3AuthResult.newBuilder()
                .setReject(Reject.newBuilder()
                    .setCode(Reject.Code.Error)
                    .setReason(e.getMessage() != null ? e.getMessage() : e.toString())
                    .build())
                .build());
        }
    }

    @Override
    public CompletableFuture<MQTT5AuthResult> auth(MQTT5AuthData authData) {
        assert !stopped.get();
        Timer.Sample start = Timer.start();
        try {
            return delegate.auth(authData)
                .handle((v, e) -> {
                    if (e != null) {
                        metricMgr.authCallErrorCounter.increment();
                        return MQTT5AuthResult.newBuilder()
                            .setFailed(Failed.newBuilder()
                                .setCode(Failed.Code.Error)
                                .setReason(e.getMessage() != null ? e.getMessage() : e.toString())
                                .build())
                            .build();
                    } else {
                        start.stop(metricMgr.authCallTimer);
                        return v;
                    }
                });
        } catch (Throwable e) {
            metricMgr.authCallErrorCounter.increment();
            log.warn("Unexpected error", e);
            Failed.Builder rb = Failed.newBuilder().setCode(Failed.Code.Error);
            if (e.getMessage() != null) {
                rb.setReason(e.getMessage());
            }
            return CompletableFuture.completedFuture(MQTT5AuthResult.newBuilder()
                .setFailed(Failed.newBuilder()
                    .setCode(Failed.Code.Error)
                    .setReason(e.getMessage() != null ? e.getMessage() : e.toString())
                    .build())
                .build());
        }
    }

    @Override
    public CompletableFuture<MQTT5ExtendedAuthResult> extendedAuth(MQTT5ExtendedAuthData authData) {
        assert !stopped.get();
        Timer.Sample start = Timer.start();
        try {
            return delegate.extendedAuth(authData)
                .handle((v, e) -> {
                    if (e != null) {
                        metricMgr.extAuthCallErrorCounter.increment();
                        return MQTT5ExtendedAuthResult.newBuilder()
                            .setFailed(Failed.newBuilder()
                                .setCode(Failed.Code.Error)
                                .setReason(e.getMessage() != null ? e.getMessage() : e.toString())
                                .build())
                            .build();
                    } else {
                        start.stop(metricMgr.extAuthCallTimer);
                        return v;
                    }
                });
        } catch (Throwable e) {
            metricMgr.extAuthCallErrorCounter.increment();
            log.warn("Unexpected error", e);
            Failed.Builder rb = Failed.newBuilder().setCode(Failed.Code.Error);
            if (e.getMessage() != null) {
                rb.setReason(e.getMessage());
            }
            return CompletableFuture.completedFuture(MQTT5ExtendedAuthResult.newBuilder()
                .setFailed(Failed.newBuilder()
                    .setCode(Failed.Code.Error)
                    .setReason(e.getMessage() != null ? e.getMessage() : e.toString())
                    .build())
                .build());
        }
    }

    @Override
    public CompletableFuture<Boolean> check(ClientInfo client, MQTTAction action) {
        log.warn(
            "IAuthProvider/check method has been deprecated and will be removed in later release, please implement checkPermission instead");
        return delegate.check(client, action);
    }

    @Override
    public CompletableFuture<CheckResult> checkPermission(ClientInfo client, MQTTAction action) {
        assert !stopped.get();
        Timer.Sample start = Timer.start();
        try {
            return delegate.checkPermission(client, action)
                .thenApply(v -> {
                    start.stop(metricMgr.checkCallTimer);
                    return v;
                })
                .exceptionally(e -> {
                    metricMgr.checkCallErrorCounter.increment();
                    eventCollector.report(getLocal(AccessControlError.class).clientInfo(client).cause(e));
                    boolean byPass = settingProvider.provide(ByPassPermCheckError, client.getTenantId());
                    if (byPass) {
                        return CheckResult.newBuilder()
                            .setGranted(Granted.getDefaultInstance())
                            .build();
                    } else {
                        log.error("Permission check error", e);
                        return CheckResult.newBuilder()
                            .setError(Error.newBuilder()
                                .setReason("Permission check error")
                                .build())
                            .build();
                    }
                });
        } catch (Throwable e) {
            metricMgr.checkCallErrorCounter.increment();
            eventCollector.report(getLocal(AccessControlError.class).clientInfo(client).cause(e));
            boolean byPass = settingProvider.provide(ByPassPermCheckError, client.getTenantId());
            if (byPass) {
                return CompletableFuture.completedFuture(CheckResult.newBuilder()
                    .setGranted(Granted.getDefaultInstance())
                    .build());
            } else {
                log.error("Permission check error", e);
                return CompletableFuture.completedFuture(CheckResult.newBuilder()
                    .setError(Error.newBuilder().setReason("Permission check error").build())
                    .build());
            }
        }
    }

    public void close() {
        if (stopped.compareAndSet(false, true)) {
            log.info("Closing auth provider manager");
            delegate.close();
            metricMgr.close();
            log.info("Auth provider manager stopped");
        }
    }
}
