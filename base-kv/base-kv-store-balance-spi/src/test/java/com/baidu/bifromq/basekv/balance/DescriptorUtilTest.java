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

package com.baidu.bifromq.basekv.balance;

import static com.baidu.bifromq.basekv.balance.DescriptorUtil.getLeastEpoch;
import static com.baidu.bifromq.basekv.balance.DescriptorUtil.organizeByEpoch;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import com.baidu.bifromq.basekv.proto.Boundary;
import com.baidu.bifromq.basekv.proto.KVRangeDescriptor;
import com.baidu.bifromq.basekv.proto.KVRangeId;
import com.baidu.bifromq.basekv.proto.KVRangeStoreDescriptor;
import com.google.protobuf.ByteString;
import java.util.HashSet;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import org.testng.annotations.Test;

public class DescriptorUtilTest {

    @Test
    public void testOrganizeByEpochSingleEpoch() {
        // Prepare data with a single epoch
        KVRangeId kvRangeId = KVRangeId.newBuilder().setEpoch(1).setId(1).build();
        KVRangeDescriptor kvRangeDescriptor = KVRangeDescriptor.newBuilder()
            .setId(kvRangeId)
            .setBoundary(Boundary.newBuilder()
                .setStartKey(ByteString.copyFromUtf8("a"))
                .setEndKey(ByteString.copyFromUtf8("z"))
                .build())
            .build();

        KVRangeStoreDescriptor storeDescriptor = KVRangeStoreDescriptor.newBuilder()
            .setId("store1")
            .putStatistics("stat1", 1.0)
            .addRanges(kvRangeDescriptor)
            .setHlc(12345L)
            .build();

        Set<KVRangeStoreDescriptor> storeDescriptors = new HashSet<>();
        storeDescriptors.add(storeDescriptor);

        // Execute the method
        Map<Long, Set<KVRangeStoreDescriptor>> result = organizeByEpoch(storeDescriptors);

        // Validate the result
        assertEquals(result.size(), 1);
        assertTrue(result.containsKey(1L));
        Set<KVRangeStoreDescriptor> epochSet = result.get(1L);
        assertEquals(epochSet.size(), 1);
        KVRangeStoreDescriptor resultDescriptor = epochSet.iterator().next();
        assertEquals(resultDescriptor.getId(), "store1");
        assertEquals(resultDescriptor.getRangesCount(), 1);
        assertEquals(resultDescriptor.getRanges(0).getId(), kvRangeId);
    }

    @Test
    public void testOrganizeByEpochMultipleEpochs() {
        // Prepare data with multiple epochs
        KVRangeId kvRangeId1 = KVRangeId.newBuilder().setEpoch(1).setId(1).build();
        KVRangeDescriptor kvRangeDescriptor1 = KVRangeDescriptor.newBuilder()
            .setId(kvRangeId1)
            .setBoundary(Boundary.newBuilder()
                .setStartKey(ByteString.copyFromUtf8("a"))
                .setEndKey(ByteString.copyFromUtf8("m"))
                .build())
            .build();

        KVRangeId kvRangeId2 = KVRangeId.newBuilder().setEpoch(2).setId(1).build();
        KVRangeDescriptor kvRangeDescriptor2 = KVRangeDescriptor.newBuilder()
            .setId(kvRangeId2)
            .setBoundary(Boundary.newBuilder()
                .setStartKey(ByteString.copyFromUtf8("n"))
                .setEndKey(ByteString.copyFromUtf8("z"))
                .build())
            .build();

        KVRangeStoreDescriptor storeDescriptor1 = KVRangeStoreDescriptor.newBuilder()
            .setId("store1")
            .putStatistics("stat1", 1.0)
            .addRanges(kvRangeDescriptor1)
            .setHlc(12345L)
            .build();

        KVRangeStoreDescriptor storeDescriptor2 = KVRangeStoreDescriptor.newBuilder()
            .setId("store2")
            .putStatistics("stat2", 2.0)
            .addRanges(kvRangeDescriptor2)
            .setHlc(67890L)
            .build();

        Set<KVRangeStoreDescriptor> storeDescriptors = new HashSet<>();
        storeDescriptors.add(storeDescriptor1);
        storeDescriptors.add(storeDescriptor2);

        // Execute the method
        Map<Long, Set<KVRangeStoreDescriptor>> result = organizeByEpoch(storeDescriptors);

        // Validate the result
        assertEquals(result.size(), 2);
        assertTrue(result.containsKey(1L));
        assertTrue(result.containsKey(2L));

        // Validate epoch 1
        Set<KVRangeStoreDescriptor> epoch1Set = result.get(1L);
        assertEquals(epoch1Set.size(), 2);

        // Validate epoch 2
        Set<KVRangeStoreDescriptor> epoch2Set = result.get(2L);
        assertEquals(epoch2Set.size(), 2);
    }

    @Test
    public void testOrganizeByEpochMultipleRangesSameStoreDifferentEpochs() {
        // Prepare data with multiple ranges in the same store but different epochs
        KVRangeId kvRangeId1 = KVRangeId.newBuilder().setEpoch(1).setId(1).build();
        KVRangeDescriptor kvRangeDescriptor1 = KVRangeDescriptor.newBuilder()
            .setId(kvRangeId1)
            .setBoundary(Boundary.newBuilder()
                .setStartKey(ByteString.copyFromUtf8("a"))
                .setEndKey(ByteString.copyFromUtf8("m"))
                .build())
            .build();

        KVRangeId kvRangeId2 = KVRangeId.newBuilder().setEpoch(2).setId(1).build();
        KVRangeDescriptor kvRangeDescriptor2 = KVRangeDescriptor.newBuilder()
            .setId(kvRangeId2)
            .setBoundary(Boundary.newBuilder()
                .setStartKey(ByteString.copyFromUtf8("n"))
                .setEndKey(ByteString.copyFromUtf8("z"))
                .build())
            .build();

        KVRangeStoreDescriptor storeDescriptor = KVRangeStoreDescriptor.newBuilder()
            .setId("store1")
            .putStatistics("stat1", 1.0)
            .addRanges(kvRangeDescriptor1)
            .addRanges(kvRangeDescriptor2)
            .setHlc(12345L)
            .build();

        Set<KVRangeStoreDescriptor> storeDescriptors = new HashSet<>();
        storeDescriptors.add(storeDescriptor);

        // Execute the method
        Map<Long, Set<KVRangeStoreDescriptor>> result = organizeByEpoch(storeDescriptors);

        // Validate the result
        assertEquals(result.size(), 2);
        assertTrue(result.containsKey(1L));
        assertTrue(result.containsKey(2L));

        // Validate epoch 1
        Set<KVRangeStoreDescriptor> epoch1Set = result.get(1L);
        assertEquals(epoch1Set.size(), 1);
        KVRangeStoreDescriptor resultDescriptor1 = epoch1Set.iterator().next();
        assertEquals(resultDescriptor1.getId(), "store1");
        assertEquals(resultDescriptor1.getRangesCount(), 1);
        assertEquals(resultDescriptor1.getRanges(0).getId(), kvRangeId1);

        // Validate epoch 2
        Set<KVRangeStoreDescriptor> epoch2Set = result.get(2L);
        assertEquals(epoch2Set.size(), 1);
        KVRangeStoreDescriptor resultDescriptor2 = epoch2Set.iterator().next();
        assertEquals(resultDescriptor2.getId(), "store1");
        assertEquals(resultDescriptor2.getRangesCount(), 1);
        assertEquals(resultDescriptor2.getRanges(0).getId(), kvRangeId2);
    }

    @Test
    public void testOrganizeByEpochWithMissingEpoch() {
        // Prepare data with multiple epochs and some missing epochs
        KVRangeId kvRangeId1 = KVRangeId.newBuilder().setEpoch(1).setId(1).build();
        KVRangeDescriptor kvRangeDescriptor1 = KVRangeDescriptor.newBuilder()
            .setId(kvRangeId1)
            .setBoundary(Boundary.newBuilder()
                .setStartKey(ByteString.copyFromUtf8("a"))
                .setEndKey(ByteString.copyFromUtf8("m"))
                .build())
            .build();

        KVRangeId kvRangeId2 = KVRangeId.newBuilder().setEpoch(2).setId(2).build();
        KVRangeDescriptor kvRangeDescriptor2 = KVRangeDescriptor.newBuilder()
            .setId(kvRangeId2)
            .setBoundary(Boundary.newBuilder()
                .setStartKey(ByteString.copyFromUtf8("n"))
                .setEndKey(ByteString.copyFromUtf8("z"))
                .build())
            .build();

        KVRangeStoreDescriptor storeDescriptor1 = KVRangeStoreDescriptor.newBuilder()
            .setId("store1")
            .putStatistics("stat1", 1.0)
            .addRanges(kvRangeDescriptor1)
            .addRanges(kvRangeDescriptor2)
            .setHlc(12345L)
            .build();

        Set<KVRangeStoreDescriptor> storeDescriptors = new HashSet<>();
        storeDescriptors.add(storeDescriptor1);

        // Execute the method
        NavigableMap<Long, Set<KVRangeStoreDescriptor>> result = DescriptorUtil.organizeByEpoch(storeDescriptors);

        // Validate the result
        assertEquals(result.size(), 2);
        assertTrue(result.containsKey(1L));
        assertTrue(result.containsKey(2L));

        // Validate epoch 1
        Set<KVRangeStoreDescriptor> epoch1Set = result.get(1L);
        assertEquals(epoch1Set.size(), 1);
        KVRangeStoreDescriptor resultDescriptor1 = epoch1Set.iterator().next();
        assertEquals(resultDescriptor1.getId(), "store1");
        assertEquals(resultDescriptor1.getRangesCount(), 1);
        assertEquals(resultDescriptor1.getRanges(0).getId().getEpoch(), 1L);

        // Validate epoch 2
        Set<KVRangeStoreDescriptor> epoch2Set = result.get(2L);
        assertEquals(epoch2Set.size(), 1);
        KVRangeStoreDescriptor resultDescriptor2 = epoch2Set.iterator().next();
        assertEquals(resultDescriptor2.getId(), "store1");
        assertEquals(resultDescriptor2.getRangesCount(), 1);
        assertEquals(resultDescriptor2.getRanges(0).getId().getEpoch(), 2L);
    }

    @Test
    public void testOrganizeByEpochWithEmptyKVRangeDescriptorInEpoch() {
        // Prepare data with multiple epochs, but some epoch lacks KVRangeDescriptor
        KVRangeId kvRangeId1 = KVRangeId.newBuilder().setEpoch(1).setId(1).build();
        KVRangeDescriptor kvRangeDescriptor1 = KVRangeDescriptor.newBuilder()
            .setId(kvRangeId1)
            .setBoundary(Boundary.newBuilder()
                .setStartKey(ByteString.copyFromUtf8("a"))
                .setEndKey(ByteString.copyFromUtf8("m"))
                .build())
            .build();

        KVRangeStoreDescriptor storeDescriptor1 = KVRangeStoreDescriptor.newBuilder()
            .setId("store1")
            .putStatistics("stat1", 1.0)
            .addRanges(kvRangeDescriptor1)
            .setHlc(12345L)
            .build();

        KVRangeStoreDescriptor storeDescriptor2 = KVRangeStoreDescriptor.newBuilder()
            .setId("store2")
            .putStatistics("stat2", 2.0)
            .setHlc(67890L) // No KVRangeDescriptor for this store in epoch 1 or 2
            .build();

        Set<KVRangeStoreDescriptor> storeDescriptors = new HashSet<>();
        storeDescriptors.add(storeDescriptor1);
        storeDescriptors.add(storeDescriptor2);

        // Execute the method
        NavigableMap<Long, Set<KVRangeStoreDescriptor>> result = DescriptorUtil.organizeByEpoch(storeDescriptors);

        // Validate the result
        assertEquals(result.size(), 1); // Should contain two epochs
        assertTrue(result.containsKey(1L));
        assertEquals(result.get(1L).size(), 2);

        // Validate epoch 1
        Set<KVRangeStoreDescriptor> epoch1Set = result.get(1L);
        assertEquals(epoch1Set.size(), 2);
        for (KVRangeStoreDescriptor descriptor : epoch1Set) {
            if (descriptor.getId().equals("store1")) {
                assertEquals(descriptor.getRangesCount(), 1);
                assertEquals(descriptor.getRanges(0).getId().getEpoch(), 1L);
            } else if (descriptor.getId().equals("store2")) {
                assertEquals(descriptor.getRangesCount(), 0); // No ranges in epoch 1 for store2
            }
        }
    }

    @Test
    public void testOrganizeByEpochAllEmptyEpochs() {
        // Prepare data with stores having no ranges but different epochs
        KVRangeStoreDescriptor storeDescriptor1 = KVRangeStoreDescriptor.newBuilder()
            .setId("store1")
            .putStatistics("stat1", 1.0)
            .setHlc(12345L)
            .build();

        KVRangeStoreDescriptor storeDescriptor2 = KVRangeStoreDescriptor.newBuilder()
            .setId("store2")
            .putStatistics("stat2", 2.0)
            .setHlc(67890L)
            .build();

        Set<KVRangeStoreDescriptor> storeDescriptors = new HashSet<>();
        storeDescriptors.add(storeDescriptor1);
        storeDescriptors.add(storeDescriptor2);

        // Execute the method
        NavigableMap<Long, Set<KVRangeStoreDescriptor>> result = DescriptorUtil.organizeByEpoch(storeDescriptors);

        // Validate the result
        assertTrue(result.isEmpty()); // Should be empty since no epochs or ranges are present
    }

    @Test
    public void testGetLeastEpochSingleEpoch() {
        // Prepare data with a single epoch
        KVRangeId kvRangeId = KVRangeId.newBuilder().setEpoch(1).setId(1).build();
        KVRangeDescriptor kvRangeDescriptor = KVRangeDescriptor.newBuilder()
            .setId(kvRangeId)
            .setBoundary(Boundary.newBuilder()
                .setStartKey(ByteString.copyFromUtf8("a"))
                .setEndKey(ByteString.copyFromUtf8("z"))
                .build())
            .build();

        KVRangeStoreDescriptor storeDescriptor = KVRangeStoreDescriptor.newBuilder()
            .setId("store1")
            .putStatistics("stat1", 1.0)
            .addRanges(kvRangeDescriptor)
            .setHlc(12345L)
            .build();

        Set<KVRangeStoreDescriptor> storeDescriptors = new HashSet<>();
        storeDescriptors.add(storeDescriptor);

        // Execute the method
        Set<KVRangeStoreDescriptor> result = getLeastEpoch(storeDescriptors);

        // Validate the result
        assertEquals(result.size(), 1);
        assertTrue(result.contains(storeDescriptor));
    }

    @Test
    public void testGetLeastEpochMultipleEpochs() {
        // Prepare data with multiple epochs
        KVRangeId kvRangeId1 = KVRangeId.newBuilder().setEpoch(1).setId(1).build();
        KVRangeDescriptor kvRangeDescriptor1 = KVRangeDescriptor.newBuilder()
            .setId(kvRangeId1)
            .setBoundary(Boundary.newBuilder()
                .setStartKey(ByteString.copyFromUtf8("a"))
                .setEndKey(ByteString.copyFromUtf8("m"))
                .build())
            .build();

        KVRangeId kvRangeId2 = KVRangeId.newBuilder().setEpoch(2).setId(1).build();
        KVRangeDescriptor kvRangeDescriptor2 = KVRangeDescriptor.newBuilder()
            .setId(kvRangeId2)
            .setBoundary(Boundary.newBuilder()
                .setStartKey(ByteString.copyFromUtf8("n"))
                .setEndKey(ByteString.copyFromUtf8("z"))
                .build())
            .build();

        KVRangeStoreDescriptor storeDescriptor1 = KVRangeStoreDescriptor.newBuilder()
            .setId("store1")
            .putStatistics("stat1", 1.0)
            .addRanges(kvRangeDescriptor1)
            .setHlc(12345L)
            .build();

        KVRangeStoreDescriptor storeDescriptor2 = KVRangeStoreDescriptor.newBuilder()
            .setId("store2")
            .putStatistics("stat2", 2.0)
            .addRanges(kvRangeDescriptor2)
            .setHlc(67890L)
            .build();

        Set<KVRangeStoreDescriptor> storeDescriptors = new HashSet<>();
        storeDescriptors.add(storeDescriptor1);
        storeDescriptors.add(storeDescriptor2);

        // Execute the method
        Set<KVRangeStoreDescriptor> result = getLeastEpoch(storeDescriptors);

        // Validate the result
        assertEquals(result.size(), 2);
        assertTrue(result.contains(storeDescriptor1));
    }

    @Test
    public void testGetLeastEpochEmptySet() {
        // Prepare an empty set of store descriptors
        Set<KVRangeStoreDescriptor> storeDescriptors = new HashSet<>();

        // Execute the method
        Set<KVRangeStoreDescriptor> result = getLeastEpoch(storeDescriptors);

        // Validate the result
        assertTrue(result.isEmpty());
    }

    @Test
    public void testGetLeastEpochMultipleRangesSameStoreDifferentEpochs() {
        // Prepare data with multiple ranges in the same store but different epochs
        KVRangeId kvRangeId1 = KVRangeId.newBuilder().setEpoch(1).setId(1).build();
        KVRangeDescriptor kvRangeDescriptor1 = KVRangeDescriptor.newBuilder()
            .setId(kvRangeId1)
            .setBoundary(Boundary.newBuilder()
                .setStartKey(ByteString.copyFromUtf8("a"))
                .setEndKey(ByteString.copyFromUtf8("m"))
                .build())
            .build();

        KVRangeId kvRangeId2 = KVRangeId.newBuilder().setEpoch(2).setId(1).build();
        KVRangeDescriptor kvRangeDescriptor2 = KVRangeDescriptor.newBuilder()
            .setId(kvRangeId2)
            .setBoundary(Boundary.newBuilder()
                .setStartKey(ByteString.copyFromUtf8("n"))
                .setEndKey(ByteString.copyFromUtf8("z"))
                .build())
            .build();

        KVRangeStoreDescriptor storeDescriptor = KVRangeStoreDescriptor.newBuilder()
            .setId("store1")
            .putStatistics("stat1", 1.0)
            .addRanges(kvRangeDescriptor1)
            .addRanges(kvRangeDescriptor2)
            .setHlc(12345L)
            .build();

        Set<KVRangeStoreDescriptor> storeDescriptors = new HashSet<>();
        storeDescriptors.add(storeDescriptor);

        // Execute the method
        Set<KVRangeStoreDescriptor> result = getLeastEpoch(storeDescriptors);

        // Validate the result
        assertEquals(result.size(), 1);
        KVRangeStoreDescriptor resultDescriptor = result.iterator().next();
        assertEquals(resultDescriptor.getRangesCount(), 1);
        assertEquals(resultDescriptor.getRanges(0).getId().getEpoch(), 1L);
        assertEquals(resultDescriptor.getRanges(0).getId(), kvRangeId1);
    }
}