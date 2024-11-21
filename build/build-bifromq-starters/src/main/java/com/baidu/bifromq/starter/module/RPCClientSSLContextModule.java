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

package com.baidu.bifromq.starter.module;

import static com.baidu.bifromq.starter.utils.ResourceUtil.loadFile;
import static com.baidu.bifromq.starter.module.SSLUtil.defaultSslProvider;

import com.baidu.bifromq.starter.config.StandaloneConfig;
import com.baidu.bifromq.starter.config.model.SSLContextConfig;
import com.google.common.base.Strings;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.TypeLiteral;
import com.google.inject.name.Names;
import io.grpc.netty.GrpcSslContexts;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import java.util.Optional;

public class RPCClientSSLContextModule extends AbstractModule {
    private static class RPCClientSSLContextProvider implements Provider<Optional<SslContext>> {
        private final StandaloneConfig config;

        @Inject
        private RPCClientSSLContextProvider(StandaloneConfig config) {
            this.config = config;
        }

        @Override
        public Optional<SslContext> get() {
            if (config.getRpcConfig().isEnableSSL()) {
                return Optional.of(buildRPCClientSslContext(config.getRpcConfig().getClientSSLConfig()));
            }
            return Optional.empty();
        }

        protected SslContext buildRPCClientSslContext(SSLContextConfig config) {

            try {
                SslProvider sslProvider = defaultSslProvider();
                SslContextBuilder sslCtxBuilder = GrpcSslContexts.forClient()
                    .sslProvider(sslProvider);
                if (config.getCertFile() != null && config.getKeyFile() != null) {
                    sslCtxBuilder.keyManager(loadFile(config.getCertFile()), loadFile(config.getKeyFile()));
                }
                if (Strings.isNullOrEmpty(config.getTrustCertsFile())) {
                    sslCtxBuilder.trustManager(InsecureTrustManagerFactory.INSTANCE);
                } else {
                    sslCtxBuilder.trustManager(loadFile(config.getTrustCertsFile()));
                }
                return sslCtxBuilder.build();
            } catch (Throwable e) {
                throw new RuntimeException("Fail to initialize RPC client SSLContext", e);
            }
        }
    }

    @Override
    protected void configure() {
        bind(new TypeLiteral<Optional<SslContext>>() {
        }).annotatedWith(Names.named("rpcClientSSLContext"))
            .toProvider(RPCClientSSLContextProvider.class)
            .asEagerSingleton();
    }
}
