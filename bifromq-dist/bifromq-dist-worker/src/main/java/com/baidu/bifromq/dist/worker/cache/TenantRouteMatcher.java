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

package com.baidu.bifromq.dist.worker.cache;

import static com.baidu.bifromq.basekv.utils.BoundaryUtil.compare;
import static com.baidu.bifromq.basekv.utils.BoundaryUtil.intersect;
import static com.baidu.bifromq.dist.entity.EntityUtil.matchRecordKeyPrefix;
import static com.baidu.bifromq.dist.entity.EntityUtil.matchRecordPrefixWithEscapedTopicFilter;
import static com.baidu.bifromq.dist.entity.EntityUtil.parseMatchRecord;
import static com.baidu.bifromq.dist.entity.EntityUtil.tenantUpperBound;
import static com.baidu.bifromq.util.TopicConst.NUL;

import com.baidu.bifromq.basekv.proto.Boundary;
import com.baidu.bifromq.basekv.store.api.IKVIterator;
import com.baidu.bifromq.basekv.store.api.IKVReader;
import com.baidu.bifromq.dist.entity.Matching;
import com.baidu.bifromq.dist.trie.TopicFilterIterator;
import com.baidu.bifromq.dist.trie.TopicTrieNode;
import com.baidu.bifromq.util.TopicUtil;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.Timer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

class TenantRouteMatcher implements ITenantRouteMatcher {
    private final String tenantId;
    private final Timer timer;
    private final Supplier<IKVReader> kvReaderSupplier;

    public TenantRouteMatcher(String tenantId, Supplier<IKVReader> kvReaderSupplier, Timer timer) {
        this.tenantId = tenantId;
        this.timer = timer;
        this.kvReaderSupplier = kvReaderSupplier;
    }

    @Override
    public Map<String, Set<Matching>> matchAll(Set<String> topics) {
        final Timer.Sample sample = Timer.start();
        Map<String, Set<Matching>> matchedRoutes = new HashMap<>();
        TopicTrieNode.Builder<String> topicTrieBuilder = TopicTrieNode.builder(false);
        topics.forEach(topic -> {
            topicTrieBuilder.addTopic(TopicUtil.parse(topic, false), topic);
            matchedRoutes.put(topic, new HashSet<>());
        });
        TopicFilterIterator<String> expansionSetItr = new TopicFilterIterator<>(topicTrieBuilder.build());

        Map<String, Set<String>> matchedTopicFilters = new HashMap<>();
        int probe = 0;

        IKVReader rangeReader = kvReaderSupplier.get();
        rangeReader.refresh();

        Boundary tenantBoundary = intersect(Boundary.newBuilder()
            .setStartKey(matchRecordKeyPrefix(tenantId))
            .setEndKey(tenantUpperBound(tenantId))
            .build(), rangeReader.boundary());

        IKVIterator itr = rangeReader.iterator();
        // track seek
        itr.seek(tenantBoundary.getStartKey());
        while (itr.isValid() && compare(itr.key(), tenantBoundary.getEndKey()) < 0) {
            // track itr.key()
            Matching matching = parseMatchRecord(itr.key(), itr.value());
            // key: topic
            Set<String> matchedTopics = matchedTopicFilters.get(matching.escapedTopicFilter);
            if (matchedTopics == null) {
                List<String> seekTopicFilter = TopicUtil.parse(matching.escapedTopicFilter, true);
                expansionSetItr.seek(seekTopicFilter);
                if (expansionSetItr.isValid()) {
                    List<String> topicFilterToMatch = expansionSetItr.key();
                    if (topicFilterToMatch.equals(seekTopicFilter)) {
                        Set<String> backingTopics = new HashSet<>();
                        for (Set<String> topicSet : expansionSetItr.value().values()) {
                            for (String topic : topicSet) {
                                matchedRoutes.computeIfAbsent(topic, k -> new HashSet<>()).add(matching);
                                backingTopics.add(topic);
                            }
                        }
                        matchedTopicFilters.put(matching.escapedTopicFilter, backingTopics);
                        itr.next();
                        probe = 0;
                    } else {
                        // next() is much cheaper than seek(), we probe following 20 entries
                        if (probe++ < 20) {
                            // probe next
                            itr.next();
                        } else {
                            // seek to match next topic filter
                            ByteString nextMatch = matchRecordPrefixWithEscapedTopicFilter(tenantId,
                                TopicUtil.fastJoin(NUL, topicFilterToMatch));
                            if (ByteString.unsignedLexicographicalComparator().compare(nextMatch, itr.key()) <= 0) {
                                // TODO: THIS IS ONLY FOR BYPASSING THE HANG ISSUE CAUSED BY PREVIOUS BUGGY MATCH_RECORD_KEY ENCODING
                                // AND WILL BE REMOVED IN LATER VERSION
                                itr.next();
                            } else {
                                itr.seek(nextMatch);
                            }
                        }
                    }
                } else {
                    break; // no more topic filter to match, stop here
                }
            } else {
                itr.next();
                for (String topic : matchedTopics) {
                    matchedRoutes.computeIfAbsent(topic, k -> new HashSet<>()).add(matching);
                }
            }
        }
        sample.stop(timer);
        return matchedRoutes;
    }

}