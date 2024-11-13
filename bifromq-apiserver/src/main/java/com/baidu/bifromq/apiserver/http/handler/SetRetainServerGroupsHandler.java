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

import com.baidu.bifromq.baserpc.trafficgovernor.IRPCServiceTrafficGovernor;
import com.baidu.bifromq.retain.client.IRetainClient;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.ws.rs.Path;

@Path("/groups/server/retain")
public class SetRetainServerGroupsHandler extends SetServerGroupsHandler {
    private final IRPCServiceTrafficGovernor trafficGovernor;

    public SetRetainServerGroupsHandler(IRetainClient retainClient) {
        this.trafficGovernor = retainClient.trafficGovernor();
    }

    @Override
    protected CompletableFuture<Void> setServerGroups(String serverId, Set<String> groupTags) {
        return trafficGovernor.setServerGroups(serverId, groupTags);
    }
}