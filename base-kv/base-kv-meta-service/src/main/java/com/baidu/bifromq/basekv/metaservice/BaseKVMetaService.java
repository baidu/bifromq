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

package com.baidu.bifromq.basekv.metaservice;

import com.baidu.bifromq.basecrdt.service.ICRDTService;
import io.reactivex.rxjava3.core.Observable;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class BaseKVMetaService implements IBaseKVMetaService {
    private final ICRDTService crdtService;
    private final Map<String, BaseKVClusterMetadataManager> metadataManagers = new ConcurrentHashMap<>();
    private final Duration proposalTimeout;

    BaseKVMetaService(ICRDTService crdtService) {
        this(crdtService, Duration.ofSeconds(5));
    }

    BaseKVMetaService(ICRDTService crdtService, Duration proposalTimeout) {
        this.crdtService = crdtService;
        this.proposalTimeout = proposalTimeout;
    }

    @Override
    public Observable<Set<String>> clusterIds() {
        return crdtService.aliveCRDTs().map(crdtUris -> crdtUris.stream()
                .filter(NameUtil::isLandscapeURI)
                .map(NameUtil::parseClusterId)
                .collect(Collectors.toSet()))
            .distinctUntilChanged();
    }

    @Override
    public IBaseKVClusterMetadataManager metadataManager(String clusterId) {
        return metadataManagers.computeIfAbsent(clusterId,
            k -> new BaseKVClusterMetadataManager(clusterId, crdtService, proposalTimeout));
    }

    @Override
    public void close() {
        metadataManagers.values().forEach(BaseKVClusterMetadataManager::stop);
    }
}