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

package com.baidu.bifromq.starter.config.model.dist;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DistWorkerClientConfig {
    private int workerThreads = Math.max(2, Runtime.getRuntime().availableProcessors() / 8);
    private int queryPipelinePerStore = 1000;
}
