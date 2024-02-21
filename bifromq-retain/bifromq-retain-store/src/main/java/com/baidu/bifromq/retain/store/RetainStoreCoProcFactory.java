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

package com.baidu.bifromq.retain.store;

import static com.baidu.bifromq.basekv.utils.BoundaryUtil.upperBound;
import static com.baidu.bifromq.retain.utils.KeyUtil.parseTenantNS;

import com.baidu.bifromq.basekv.proto.KVRangeId;
import com.baidu.bifromq.basekv.store.api.IKVRangeCoProc;
import com.baidu.bifromq.basekv.store.api.IKVRangeCoProcFactory;
import com.baidu.bifromq.basekv.store.api.IKVRangeSplitHinter;
import com.baidu.bifromq.basekv.store.api.IKVReader;
import com.baidu.bifromq.basekv.store.range.hinter.MutationKVLoadBasedSplitHinter;
import com.baidu.bifromq.basekv.utils.KVRangeIdUtil;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class RetainStoreCoProcFactory implements IKVRangeCoProcFactory {
    private final Duration loadEstWindow;
    private final Map<String, Long> tenantRetainSpaceMap = new ConcurrentHashMap<>();
    private final Map<String, Long> tenantRetainCountMap = new ConcurrentHashMap<>();

    public RetainStoreCoProcFactory(Duration loadEstimateWindow) {
        this.loadEstWindow = loadEstimateWindow;
    }

    @Override
    public List<IKVRangeSplitHinter> createHinters(String clusterId, String storeId, KVRangeId id,
                                                   Supplier<IKVReader> rangeReaderProvider) {
        return Collections.singletonList(
            new MutationKVLoadBasedSplitHinter(loadEstWindow, key -> {
                // make sure retain message from one tenant do no cross range
                return Optional.of(upperBound(parseTenantNS(key)));
            }, "clusterId", clusterId, "storeId", storeId, "rangeId", KVRangeIdUtil.toString(id)));
    }

    @Override
    public IKVRangeCoProc createCoProc(String clusterId,
                                       String storeId,
                                       KVRangeId id,
                                       Supplier<IKVReader> rangeReaderProvider) {
        return new RetainStoreCoProc(clusterId, storeId, id, rangeReaderProvider);
    }
}
