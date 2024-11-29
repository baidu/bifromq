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

package com.baidu.bifromq.apiserver.http.handler;

import com.baidu.bifromq.basekv.proto.Boundary;
import com.baidu.bifromq.basekv.proto.KVRangeDescriptor;
import com.baidu.bifromq.basekv.proto.KVRangeStoreDescriptor;
import com.baidu.bifromq.basekv.raft.proto.ClusterConfig;
import com.baidu.bifromq.baserpc.trafficgovernor.ServerEndpoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.ByteString;
import java.util.Base64;
import java.util.Map;

public class JSONUtils {
    public static JsonNode toJSON(Map<ServerEndpoint, KVRangeStoreDescriptor> landscape) {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode rootObject = mapper.createArrayNode();
        for (ServerEndpoint server : landscape.keySet()) {
            KVRangeStoreDescriptor storeDescriptor = landscape.get(server);
            ObjectNode storeNodeObject = mapper.createObjectNode();
            storeNodeObject.put("hostId", Base64.getEncoder().encodeToString(server.hostId().toByteArray()));
            storeNodeObject.put("id", storeDescriptor.getId());
            storeNodeObject.put("address", server.address());
            storeNodeObject.put("port", server.port());

            ObjectNode attrsObject = mapper.createObjectNode();
            for (String attrName : storeDescriptor.getAttributesMap().keySet()) {
                attrsObject.put(attrName, storeDescriptor.getAttributesMap().get(attrName));
            }
            storeNodeObject.set("attributes", attrsObject);
            rootObject.add(storeNodeObject);
        }
        return rootObject;
    }

    public static JsonNode toJSON(KVRangeDescriptor descriptor, ObjectMapper mapper) {
        ObjectNode rangeObject = mapper.createObjectNode();
        rangeObject.put("id", descriptor.getId().getEpoch() + "_" + descriptor.getId().getId());
        rangeObject.put("ver", descriptor.getVer());
        rangeObject.set("boundary", toJSON(descriptor.getBoundary(), mapper));
        rangeObject.put("state", descriptor.getState().name());
        rangeObject.put("role", descriptor.getRole().name());
        rangeObject.set("clusterConfig", toJSON(descriptor.getConfig(), mapper));
        return rangeObject;
    }

    public static JsonNode toJSON(Boundary boundary, ObjectMapper mapper) {
        ObjectNode boundaryObject = mapper.createObjectNode();
        boundaryObject.put("startKey", toHex(boundary.getStartKey()));
        boundaryObject.put("endKey", toHex(boundary.getEndKey()));
        return boundaryObject;
    }

    public static JsonNode toJSON(ClusterConfig config, ObjectMapper mapper) {
        ObjectNode clusterConfigObject = mapper.createObjectNode();

        ArrayNode votersArray = mapper.createArrayNode();
        config.getVotersList().forEach(votersArray::add);
        clusterConfigObject.set("voters", votersArray);

        ArrayNode learnersArray = mapper.createArrayNode();
        config.getLearnersList().forEach(learnersArray::add);
        clusterConfigObject.set("learners", learnersArray);

        ArrayNode nextVotersArray = mapper.createArrayNode();
        config.getNextVotersList().forEach(nextVotersArray::add);
        clusterConfigObject.set("nextVoters", nextVotersArray);

        ArrayNode nextLearnersArray = mapper.createArrayNode();
        config.getNextLearnersList().forEach(nextLearnersArray::add);
        clusterConfigObject.set("nextLearners", nextLearnersArray);

        return clusterConfigObject;
    }

    public static String toHex(ByteString bs) {
        if (bs == null || bs.isEmpty()) {
            return "";
        }

        StringBuilder hexString = new StringBuilder(bs.size() * 5);
        for (int i = 0; i < bs.size(); i++) {
            byte b = bs.byteAt(i);
            hexString.append(String.format("0x%02X", b));
            if (i < bs.size() - 1) {
                hexString.append(" ");
            }
        }
        return hexString.toString();
    }
}