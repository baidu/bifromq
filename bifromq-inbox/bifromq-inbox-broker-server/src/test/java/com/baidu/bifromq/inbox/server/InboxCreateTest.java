/*
 * Copyright (c) 2023. Baidu, Inc. All Rights Reserved.
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

package com.baidu.bifromq.inbox.server;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import com.baidu.bifromq.inbox.rpc.proto.CreateInboxReply;
import com.baidu.bifromq.inbox.rpc.proto.DeleteInboxReply;
import com.baidu.bifromq.type.ClientInfo;
import org.testng.annotations.Test;

public class InboxCreateTest extends InboxServiceTest {

    private final String tenantId = "trafficA";
    private final String inboxId = "inbox1";
    private final long reqId = System.nanoTime();
    private ClientInfo clientInfo;

    @Test(groups = "integration")
    public void create() {
        clientInfo = ClientInfo.newBuilder().setTenantId(tenantId).build();
        assertFalse(inboxReaderClient.has(reqId, inboxId, clientInfo).join());

        CreateInboxReply createInboxReply = inboxReaderClient.create(reqId, inboxId, clientInfo).join();
        assertEquals(createInboxReply.getReqId(), reqId);
        assertEquals(createInboxReply.getResult(), CreateInboxReply.Result.OK);

        assertTrue(inboxReaderClient.has(reqId, inboxId, clientInfo).join());
    }

    @Test(groups = "integration", dependsOnMethods = "create")
    public void delete() {
        DeleteInboxReply deleteInboxReply = inboxReaderClient.delete(reqId, inboxId, clientInfo).join();
        assertEquals(deleteInboxReply.getReqId(), reqId);
        assertEquals(deleteInboxReply.getResult(), DeleteInboxReply.Result.OK);

        assertFalse(inboxReaderClient.has(reqId, inboxId, clientInfo).join());
    }
}
