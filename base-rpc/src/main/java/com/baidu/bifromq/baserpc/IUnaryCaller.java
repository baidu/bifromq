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

import static java.util.Collections.emptyMap;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;

/**
 * The caller for unary RPC.
 *
 * @param <ReqT>  the request type
 * @param <RespT> the response type
 */
interface IUnaryCaller<ReqT, RespT> {
    default CompletableFuture<RespT> invoke(String tenantId, @Nullable String targetServerId, ReqT req) {
        return invoke(tenantId, targetServerId, req, emptyMap());
    }

    CompletableFuture<RespT> invoke(String tenantId,
                                    @Nullable String targetServerId,
                                    ReqT req,
                                    Map<String, String> metadata);
}