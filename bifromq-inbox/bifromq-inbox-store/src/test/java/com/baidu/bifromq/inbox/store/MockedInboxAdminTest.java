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

package com.baidu.bifromq.inbox.store;

import static com.baidu.bifromq.inbox.util.KeyUtil.scopedInboxId;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

import com.baidu.bifromq.basekv.proto.KVRangeId;
import com.baidu.bifromq.basekv.store.api.IKVIterator;
import com.baidu.bifromq.basekv.store.api.IKVRangeReader;
import com.baidu.bifromq.basekv.store.api.IKVReader;
import com.baidu.bifromq.basekv.store.api.IKVWriter;
import com.baidu.bifromq.basekv.store.range.ILoadTracker;
import com.baidu.bifromq.basekv.utils.KVRangeIdUtil;
import com.baidu.bifromq.inbox.storage.proto.BatchCheckReply;
import com.baidu.bifromq.inbox.storage.proto.BatchCheckRequest;
import com.baidu.bifromq.inbox.storage.proto.BatchCreateRequest;
import com.baidu.bifromq.inbox.storage.proto.BatchTouchRequest;
import com.baidu.bifromq.inbox.storage.proto.CreateParams;
import com.baidu.bifromq.inbox.storage.proto.GCReply;
import com.baidu.bifromq.inbox.storage.proto.GCRequest;
import com.baidu.bifromq.inbox.storage.proto.InboxMetadata;
import com.baidu.bifromq.inbox.storage.proto.InboxServiceROCoProcInput;
import com.baidu.bifromq.inbox.storage.proto.InboxServiceROCoProcOutput;
import com.baidu.bifromq.inbox.storage.proto.InboxServiceRWCoProcInput;
import com.baidu.bifromq.plugin.eventcollector.IEventCollector;
import com.baidu.bifromq.plugin.settingprovider.ISettingProvider;
import com.baidu.bifromq.plugin.settingprovider.Setting;
import com.baidu.bifromq.type.ClientInfo;
import com.google.protobuf.ByteString;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class MockedInboxAdminTest {
    private KVRangeId id;
    @Mock
    private IKVReader reader;
    @Mock
    private IKVIterator kvIterator;
    @Mock
    private IKVWriter writer;
    @Mock
    private ILoadTracker loadTracker;
    private final Supplier<IKVRangeReader> rangeReaderProvider = () -> null;
    private final ISettingProvider settingProvider = Setting::current;
    private final IEventCollector eventCollector = event -> {
    };
    private final String tenantId = "tenantA";
    private final String inboxId = "inboxId";
    private final String scopedInboxIdUtf8 = scopedInboxId(tenantId, inboxId).toStringUtf8();
    private final ClientInfo clientInfo = ClientInfo.newBuilder()
        .setTenantId(tenantId)
        .putMetadata("agent", "mqtt")
        .putMetadata("protocol", "3.1.1")
        .putMetadata("userId", "testUser")
        .putMetadata("clientId", "testClientId")
        .putMetadata("ip", "127.0.0.1")
        .putMetadata("port", "8888")
        .build();
    private final Clock clock = Clock.systemUTC();
    private AutoCloseable closeable;

    @BeforeMethod
    public void setup() {
        closeable = MockitoAnnotations.openMocks(this);
        when(reader.iterator()).thenReturn(kvIterator);
        id = KVRangeIdUtil.generate();
    }

    @AfterMethod
    public void teardown() throws Exception {
        closeable.close();
    }

    @Test
    public void testCreateNewInbox() {
        BatchCreateRequest createRequest = BatchCreateRequest.newBuilder()
            .putInboxes(scopedInboxIdUtf8, CreateParams.newBuilder()
                .setClient(clientInfo)
                .build())
            .build();
        InboxServiceRWCoProcInput coProcInput = InboxServiceRWCoProcInput.newBuilder()
            .setReqId(System.nanoTime())
            .setBatchCreate(createRequest)
            .build();

        when(reader.get(any())).thenReturn(Optional.empty());
        doNothing().when(writer).put(any(), any());

        InboxStoreCoProc coProc = new InboxStoreCoProc(id, rangeReaderProvider, settingProvider, eventCollector,
            clock, Duration.ofMinutes(30), loadTracker);
        coProc.mutate(coProcInput.toByteString(), reader, writer);
        ArgumentCaptor<ByteString> argumentCaptor = ArgumentCaptor.forClass(ByteString.class);
        verify(writer).put(argumentCaptor.capture(), argumentCaptor.capture());
        List<ByteString> args = argumentCaptor.getAllValues();

        try {
            assertEquals(args.size(), 2);
            assertEquals(ByteString.copyFromUtf8(scopedInboxIdUtf8), args.get(0));
            InboxMetadata inboxMetadata = InboxMetadata.parseFrom(args.get(1));
            assertEquals(0, inboxMetadata.getQos0NextSeq());
            assertEquals(0, inboxMetadata.getQos1NextSeq());
            assertEquals(0, inboxMetadata.getQos2NextSeq());
            assertEquals(clientInfo, inboxMetadata.getClient());
        } catch (Exception exception) {
            fail();
        }
    }

    @Test
    public void testCreateExpiredInbox() {
        BatchCreateRequest createRequest = BatchCreateRequest.newBuilder()
            .putInboxes(scopedInboxIdUtf8, CreateParams.newBuilder()
                .setClient(clientInfo)
                .build())
            .build();
        InboxServiceRWCoProcInput coProcInput = InboxServiceRWCoProcInput.newBuilder()
            .setReqId(System.nanoTime())
            .setBatchCreate(createRequest)
            .build();

        when(reader.get(any())).thenReturn(Optional.of(InboxMetadata.newBuilder()
            .setLastFetchTime(clock.millis() - 30 * 1000)
            .setQos0NextSeq(1)
            .setQos1NextSeq(1)
            .setQos2NextSeq(1)
            .setExpireSeconds(1)
            .build().toByteString()));
        doNothing().when(kvIterator).seek(any());
        when(kvIterator.isValid()).thenReturn(false);
        doNothing().when(writer).put(any(), any());

        InboxStoreCoProc coProc = new InboxStoreCoProc(id, rangeReaderProvider, settingProvider, eventCollector,
            Clock.systemUTC(), Duration.ofMinutes(30), loadTracker);
        coProc.mutate(coProcInput.toByteString(), reader, writer);
        ArgumentCaptor<ByteString> argumentCaptor = ArgumentCaptor.forClass(ByteString.class);
        verify(writer).put(argumentCaptor.capture(), argumentCaptor.capture());
        List<ByteString> args = argumentCaptor.getAllValues();

        try {
            assertEquals(args.size(), 2);
            assertEquals(ByteString.copyFromUtf8(scopedInboxIdUtf8), args.get(0));
            InboxMetadata inboxMetadata = InboxMetadata.parseFrom(args.get(1));
            assertEquals(0, inboxMetadata.getQos0NextSeq());
            assertEquals(0, inboxMetadata.getQos1NextSeq());
            assertEquals(0, inboxMetadata.getQos2NextSeq());
            assertEquals(clientInfo, inboxMetadata.getClient());
        } catch (Exception exception) {
            fail();
        }
    }

    @Test
    public void testHasInbox() {
        BatchCheckRequest.Builder hasBuilder = BatchCheckRequest.newBuilder();
        hasBuilder.addScopedInboxId(ByteString.copyFromUtf8(scopedInboxIdUtf8))
            .addScopedInboxId(ByteString.copyFromUtf8("dev-" + scopedInboxIdUtf8))
            .addScopedInboxId(ByteString.copyFromUtf8("expire-" + scopedInboxIdUtf8));
        BatchCheckRequest batchCheckRequest = hasBuilder.build();

        InboxServiceROCoProcInput roInput = InboxServiceROCoProcInput.newBuilder()
            .setReqId(System.nanoTime())
            .setBatchCheck(batchCheckRequest)
            .build();

        when(reader.get(ByteString.copyFromUtf8(scopedInboxIdUtf8))).thenReturn(Optional.empty());
        when(reader.get(ByteString.copyFromUtf8("dev-" + scopedInboxIdUtf8)))
            .thenReturn(Optional.of(InboxMetadata.newBuilder()
                .setLastFetchTime(clock.millis())
                .setExpireSeconds(Integer.MAX_VALUE)
                .build().toByteString()));
        when(reader.get(ByteString.copyFromUtf8("expire-" + scopedInboxIdUtf8)))
            .thenReturn(Optional.of(InboxMetadata.newBuilder()
                .setLastFetchTime(clock.millis() - 30 * 1000)
                .setExpireSeconds(1)
                .build().toByteString()));

        InboxStoreCoProc coProc = new InboxStoreCoProc(id, rangeReaderProvider, settingProvider, eventCollector,
            clock, Duration.ofMinutes(30), loadTracker);
        ByteString result = coProc.query(roInput.toByteString(), reader).join();

        try {
            BatchCheckReply batchCheckReply = InboxServiceROCoProcOutput.parseFrom(result).getBatchCheck();
            Assert.assertFalse(batchCheckReply.getExistsMap().get(scopedInboxIdUtf8));
            Assert.assertTrue(batchCheckReply.getExistsMap().get("dev-" + scopedInboxIdUtf8));
            Assert.assertFalse(batchCheckReply.getExistsMap().get("expire-" + scopedInboxIdUtf8));
        } catch (Exception exception) {
            fail();
        }
    }

    @Test
    public void testDeleteInbox() {
        InboxServiceRWCoProcInput coProcInput = InboxServiceRWCoProcInput.newBuilder()
            .setBatchTouch(BatchTouchRequest.newBuilder()
                .putScopedInboxId(scopedInboxIdUtf8, false)
                .putScopedInboxId("dev-" + scopedInboxIdUtf8, false)
                .putScopedInboxId("expire-" + scopedInboxIdUtf8, true)
                .build())
            .build();

        when(reader.get(ByteString.copyFromUtf8(scopedInboxIdUtf8)))
            .thenReturn(Optional.empty());
        when(reader.get(ByteString.copyFromUtf8("dev-" + scopedInboxIdUtf8)))
            .thenReturn(Optional.of(InboxMetadata.newBuilder()
                .setLastFetchTime(clock.millis())
                .setExpireSeconds(Integer.MAX_VALUE)
                .build().toByteString()));
        when(reader.get(ByteString.copyFromUtf8("expire-" + scopedInboxIdUtf8)))
            .thenReturn(Optional.of(InboxMetadata.newBuilder()
                .setLastFetchTime(clock.millis() - 30 * 1000)
                .setExpireSeconds(1)
                .build().toByteString()));
        doNothing().when(writer).delete(any());

        InboxStoreCoProc coProc = new InboxStoreCoProc(id, rangeReaderProvider, settingProvider, eventCollector,
            clock, Duration.ofMinutes(30), loadTracker);
        coProc.mutate(coProcInput.toByteString(), reader, writer);

        ArgumentCaptor<ByteString> argumentCaptor = ArgumentCaptor.forClass(ByteString.class);
        verify(writer, times(2)).delete(argumentCaptor.capture());
        List<ByteString> args = argumentCaptor.getAllValues();

        try {
            assertEquals(args.size(), 2);
            assertEquals(ByteString.copyFromUtf8("dev-" + scopedInboxIdUtf8), args.get(0));
            assertEquals(ByteString.copyFromUtf8("expire-" + scopedInboxIdUtf8), args.get(1));
        } catch (Exception exception) {
            fail();
        }
    }

    @Test
    public void testGCScan() {
        long reqId = System.nanoTime();
        InboxServiceROCoProcInput coProcInput = InboxServiceROCoProcInput.newBuilder()
            .setReqId(reqId)
            .setGc(GCRequest.newBuilder().setReqId(reqId).setLimit(10).build())
            .build();

        when(reader.get(any()))
            .thenReturn(Optional.of(InboxMetadata.newBuilder()
                .setLastFetchTime(clock.millis() - 30 * 1000)
                .setExpireSeconds(1)
                .build().toByteString()));

        when(kvIterator.isValid())
            .thenReturn(true)
            .thenReturn(false);
        when(kvIterator.key())
            .thenReturn(ByteString.copyFromUtf8(scopedInboxIdUtf8));
        when(kvIterator.value())
            .thenReturn(InboxMetadata.newBuilder()
                .setLastFetchTime(clock.millis() - 30 * 1000)
                .setExpireSeconds(1)
                .build().toByteString());

        InboxStoreCoProc coProc = new InboxStoreCoProc(id, rangeReaderProvider, settingProvider, eventCollector,
            clock, Duration.ZERO, loadTracker);
        ByteString output = coProc.query(coProcInput.toByteString(), reader).join();

        verify(kvIterator).seekToFirst();

        try {
            GCReply reply = InboxServiceROCoProcOutput.parseFrom(output).getGc();
            assertEquals(reply.getReqId(), coProcInput.getGc().getReqId());
            assertEquals(reply.getScopedInboxIdCount(), 1);
            assertEquals(reply.getScopedInboxIdList().get(0), ByteString.copyFromUtf8(scopedInboxIdUtf8));
        } catch (Exception exception) {
            fail();
        }
    }

    @Test
    public void testGCScanWithNoExpiredInbox() {
        long reqId = System.nanoTime();
        InboxServiceROCoProcInput coProcInput = InboxServiceROCoProcInput.newBuilder()
            .setReqId(reqId)
            .setGc(GCRequest.newBuilder().setReqId(reqId).setLimit(10).build())
            .build();

        when(reader.get(any()))
            .thenReturn(Optional.of(InboxMetadata.newBuilder()
                .setLastFetchTime(clock.millis())
                .setExpireSeconds(1)
                .build().toByteString()));

        when(kvIterator.isValid())
            .thenReturn(true)
            .thenReturn(false);
        when(kvIterator.key())
            .thenReturn(ByteString.copyFromUtf8(scopedInboxIdUtf8));
        when(kvIterator.value())
            .thenReturn(InboxMetadata.newBuilder()
                .setLastFetchTime(clock.millis())
                .setExpireSeconds(1)
                .build().toByteString());

        InboxStoreCoProc coProc = new InboxStoreCoProc(id, rangeReaderProvider, settingProvider, eventCollector,
            clock, Duration.ZERO, loadTracker);
        ByteString output = coProc.query(coProcInput.toByteString(), reader).join();

        verify(kvIterator).seekToFirst();

        try {
            GCReply reply = InboxServiceROCoProcOutput.parseFrom(output).getGc();
            assertEquals(reply.getReqId(), coProcInput.getGc().getReqId());
            assertEquals(reply.getScopedInboxIdCount(), 0);
        } catch (Exception exception) {
            fail();
        }
    }

    @Test
    public void testGCScanWithLimit() {
        long reqId = System.nanoTime();
        String scopedInboxId1 = scopedInboxId(tenantId, "inbox1").toStringUtf8();
        String scopedInboxId2 = scopedInboxId(tenantId, "inbox2").toStringUtf8();

        InboxServiceROCoProcInput coProcInput = InboxServiceROCoProcInput.newBuilder()
            .setReqId(reqId)
            .setGc(GCRequest.newBuilder().setReqId(reqId).setLimit(1).build())
            .build();

        when(reader.get(any()))
            .thenReturn(Optional.of(InboxMetadata.newBuilder()
                .setLastFetchTime(clock.millis() - 30 * 1000)
                .setExpireSeconds(1)
                .build().toByteString()));

        when(kvIterator.isValid())
            .thenReturn(true)
            .thenReturn(true)
            .thenReturn(false);
        when(kvIterator.key())
            .thenReturn(ByteString.copyFromUtf8(scopedInboxId1))
            .thenReturn(ByteString.copyFromUtf8(scopedInboxId2));
        when(kvIterator.value())
            .thenReturn(InboxMetadata.newBuilder()
                .setLastFetchTime(clock.millis() - 30 * 1000)
                .setExpireSeconds(1)
                .build().toByteString());

        InboxStoreCoProc coProc = new InboxStoreCoProc(id, rangeReaderProvider, settingProvider, eventCollector,
            clock, Duration.ZERO, loadTracker);
        ByteString output = coProc.query(coProcInput.toByteString(), reader).join();

        verify(kvIterator).seekToFirst();

        try {
            GCReply reply = InboxServiceROCoProcOutput.parseFrom(output).getGc();
            assertEquals(reply.getReqId(), coProcInput.getGc().getReqId());
            assertEquals(reply.getScopedInboxIdCount(), 1);
            assertEquals(reply.getScopedInboxIdList().get(0), ByteString.copyFromUtf8(scopedInboxId1));
        } catch (Exception exception) {
            fail();
        }
    }
}
