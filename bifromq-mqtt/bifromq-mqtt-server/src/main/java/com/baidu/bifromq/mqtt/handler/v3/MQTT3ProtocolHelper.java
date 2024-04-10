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

package com.baidu.bifromq.mqtt.handler.v3;

import static com.baidu.bifromq.mqtt.handler.record.ProtocolResponse.goAway;
import static com.baidu.bifromq.mqtt.handler.record.ProtocolResponse.goAwayNow;
import static com.baidu.bifromq.mqtt.handler.record.ProtocolResponse.response;
import static com.baidu.bifromq.mqtt.handler.record.ProtocolResponse.responseNothing;
import static com.baidu.bifromq.mqtt.handler.v3.MQTT3MessageUtils.toMessage;
import static com.baidu.bifromq.plugin.eventcollector.ThreadLocalEventPool.getLocal;
import static com.baidu.bifromq.type.QoS.AT_LEAST_ONCE;

import com.baidu.bifromq.dist.client.DistResult;
import com.baidu.bifromq.inbox.storage.proto.TopicFilterOption;
import com.baidu.bifromq.mqtt.handler.IMQTTProtocolHelper;
import com.baidu.bifromq.mqtt.handler.MQTTSessionHandler;
import com.baidu.bifromq.mqtt.handler.TenantSettings;
import com.baidu.bifromq.mqtt.handler.record.ProtocolResponse;
import com.baidu.bifromq.plugin.authprovider.type.CheckResult;
import com.baidu.bifromq.plugin.eventcollector.mqttbroker.clientdisconnect.BadPacket;
import com.baidu.bifromq.plugin.eventcollector.mqttbroker.clientdisconnect.ByServer;
import com.baidu.bifromq.plugin.eventcollector.mqttbroker.clientdisconnect.Idle;
import com.baidu.bifromq.plugin.eventcollector.mqttbroker.clientdisconnect.InboxTransientError;
import com.baidu.bifromq.plugin.eventcollector.mqttbroker.clientdisconnect.InvalidTopic;
import com.baidu.bifromq.plugin.eventcollector.mqttbroker.clientdisconnect.Kicked;
import com.baidu.bifromq.plugin.eventcollector.mqttbroker.clientdisconnect.MalformedTopic;
import com.baidu.bifromq.plugin.eventcollector.mqttbroker.clientdisconnect.MalformedTopicFilter;
import com.baidu.bifromq.plugin.eventcollector.mqttbroker.clientdisconnect.NoPubPermission;
import com.baidu.bifromq.plugin.eventcollector.mqttbroker.clientdisconnect.ProtocolViolation;
import com.baidu.bifromq.plugin.eventcollector.mqttbroker.clientdisconnect.ResourceThrottled;
import com.baidu.bifromq.plugin.eventcollector.mqttbroker.clientdisconnect.ServerBusy;
import com.baidu.bifromq.plugin.eventcollector.mqttbroker.clientdisconnect.TooLargeSubscription;
import com.baidu.bifromq.plugin.eventcollector.mqttbroker.clientdisconnect.TooLargeUnsubscription;
import com.baidu.bifromq.plugin.eventcollector.mqttbroker.disthandling.Discard;
import com.baidu.bifromq.plugin.eventcollector.mqttbroker.disthandling.QoS1PubAcked;
import com.baidu.bifromq.plugin.eventcollector.mqttbroker.disthandling.QoS2PubReced;
import com.baidu.bifromq.retain.rpc.proto.RetainReply;
import com.baidu.bifromq.sysprops.BifroMQSysProp;
import com.baidu.bifromq.type.ClientInfo;
import com.baidu.bifromq.type.Message;
import com.baidu.bifromq.type.QoS;
import com.baidu.bifromq.type.UserProperties;
import com.baidu.bifromq.util.TopicUtil;
import com.baidu.bifromq.util.UTF8Util;
import com.bifromq.plugin.resourcethrottler.TenantResourceType;
import io.netty.handler.codec.mqtt.MqttConnectMessage;
import io.netty.handler.codec.mqtt.MqttMessage;
import io.netty.handler.codec.mqtt.MqttMessageBuilders;
import io.netty.handler.codec.mqtt.MqttMessageIdVariableHeader;
import io.netty.handler.codec.mqtt.MqttPublishMessage;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.netty.handler.codec.mqtt.MqttSubAckMessage;
import io.netty.handler.codec.mqtt.MqttSubscribeMessage;
import io.netty.handler.codec.mqtt.MqttTopicSubscription;
import io.netty.handler.codec.mqtt.MqttUnsubAckMessage;
import io.netty.handler.codec.mqtt.MqttUnsubscribeMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class MQTT3ProtocolHelper implements IMQTTProtocolHelper {
    private static final boolean SANITY_CHECK = BifroMQSysProp.MQTT_UTF8_SANITY_CHECK.get();
    private final TenantSettings settings;
    private final ClientInfo clientInfo;

    public final UserProperties getUserProps(MqttPublishMessage mqttMessage) {
        // MQTT3: no user properties
        return UserProperties.getDefaultInstance();
    }

    public final UserProperties getUserProps(MqttUnsubscribeMessage mqttMessage) {
        // MQTT3: no user properties
        return UserProperties.getDefaultInstance();
    }


    @Override
    public boolean checkPacketIdUsage() {
        return false;
    }

    @Override
    public ProtocolResponse onInboxTransientError() {
        return goAway(getLocal(InboxTransientError.class).clientInfo(clientInfo));
    }

    @Override
    public Optional<Integer> sessionExpiryIntervalOnDisconnect(MqttMessage disconnectMessage) {
        return Optional.empty();
    }

    @Override
    public boolean isNormalDisconnect(MqttMessage message) {
        return true;
    }

    @Override
    public boolean isDisconnectWithLWT(MqttMessage message) {
        return false;
    }

    @Override
    public ProtocolResponse onDisconnect() {
        return goAwayNow((getLocal(ByServer.class).clientInfo(clientInfo)));
    }

    @Override
    public ProtocolResponse onResourceExhaustedDisconnect(TenantResourceType resourceType) {
        return goAwayNow((getLocal(ResourceThrottled.class)
            .reason(resourceType.name())
            .clientInfo(clientInfo)));
    }

    @Override
    public ProtocolResponse respondDisconnectProtocolError() {
        return goAway(getLocal(ProtocolViolation.class).statement("Never happen in mqtt3").clientInfo(clientInfo));
    }

    @Override
    public ProtocolResponse respondDecodeError(MqttMessage message) {
        return goAway(getLocal(BadPacket.class).cause(message.decoderResult().cause()).clientInfo(clientInfo));
    }

    @Override
    public ProtocolResponse respondDuplicateConnect(MqttConnectMessage message) {
        return goAway(getLocal(ProtocolViolation.class).statement("MQTT3-3.1.0-2").clientInfo(clientInfo));
    }

    @Override
    public ProtocolResponse validateSubMessage(MqttSubscribeMessage message) {
        List<MqttTopicSubscription> topicSubscriptions = message.payload().topicSubscriptions();
        if (topicSubscriptions.isEmpty()) {
            // Ignore instead of disconnect [MQTT-3.8.3-3]
            return goAway(getLocal(ProtocolViolation.class).statement("MQTT3-3.8.3-3").clientInfo(clientInfo));
        }
        if (topicSubscriptions.size() > settings.maxTopicFiltersPerSub) {
            return goAway(getLocal(TooLargeSubscription.class)
                .actual(topicSubscriptions.size())
                .max(settings.maxTopicFiltersPerSub)
                .clientInfo(clientInfo));
        }
        return null;
    }

    @Override
    public List<SubTask> getSubTask(MqttSubscribeMessage message) {
        return message.payload()
            .topicSubscriptions()
            .stream()
            .map(sub -> new SubTask(sub.topicName(),
                TopicFilterOption.newBuilder()
                    .setQos(QoS.forNumber(sub.qualityOfService().value()))
                    .build(),
                UserProperties.getDefaultInstance()
            ))
            .toList();
    }

    @Override
    public ProtocolResponse onSubBackPressured(MqttSubscribeMessage subMessage) {
        return goAway((getLocal(ServerBusy.class)
            .reason("Too many subscribe")
            .clientInfo(clientInfo)));
    }

    @Override
    public ProtocolResponse buildSubAckMessage(MqttSubscribeMessage subMessage, List<SubResult> results) {
        assert subMessage.payload().topicSubscriptions().size() == results.size();
        List<MqttQoS> grantedQoSList = new ArrayList<>(results.size());
        for (int i = 0; i < results.size(); i++) {
            switch (results.get(i)) {
                case OK, EXISTS ->
                    grantedQoSList.add(subMessage.payload().topicSubscriptions().get(i).qualityOfService());
                default -> grantedQoSList.add(MqttQoS.FAILURE);
            }
        }
        return response(MqttMessageBuilders.subAck()
            .packetId(subMessage.variableHeader().messageId())
            .addGrantedQoses(grantedQoSList.toArray(MqttQoS[]::new))
            .build());
    }

    @Override
    public MqttSubAckMessage respondPacketIdInUse(MqttSubscribeMessage message) {
        throw new UnsupportedOperationException("MQTT3 do not check packetId usage");
    }

    @Override
    public ProtocolResponse validateUnsubMessage(MqttUnsubscribeMessage message) {
        List<String> topicFilters = message.payload().topics();
        if (topicFilters.isEmpty()) {
            // Ignore instead of disconnect [3.10.3-2]
            return goAway(getLocal(ProtocolViolation.class).statement("MQTT-3.10.3-2").clientInfo(clientInfo));
        }
        if (topicFilters.size() > settings.maxTopicFiltersPerSub) {
            return goAway(getLocal(TooLargeUnsubscription.class)
                .max(settings.maxTopicFiltersPerSub)
                .actual(topicFilters.size())
                .clientInfo(clientInfo));
        }
        for (String topicFilter : topicFilters) {
            if (!UTF8Util.isWellFormed(topicFilter, SANITY_CHECK)) {
                return goAway(getLocal(MalformedTopicFilter.class)
                    .topicFilter(topicFilter)
                    .clientInfo(clientInfo));
            }
        }
        return null;
    }

    @Override
    public MqttUnsubAckMessage respondPacketIdInUse(MqttUnsubscribeMessage message) {
        throw new UnsupportedOperationException("MQTT3 do not check packetId usage");
    }

    @Override
    public ProtocolResponse onUnsubBackPressured(MqttUnsubscribeMessage unsubMessage) {
        return goAway((getLocal(ServerBusy.class)
            .reason("Too many unsubscribe")
            .clientInfo(clientInfo)));
    }

    @Override
    public ProtocolResponse buildUnsubAckMessage(MqttUnsubscribeMessage unsubMessage, List<UnsubResult> results) {
        return response(MqttMessageBuilders.unsubAck().packetId(unsubMessage.variableHeader().messageId()).build());
    }

    @Override
    public MqttMessage onPubRelReceived(MqttMessage message, boolean packetIdFound) {
        return MQTT3MessageBuilders.pubComp()
            .packetId(((MqttMessageIdVariableHeader) message.variableHeader()).messageId())
            .build();
    }

    @Override
    public boolean isQoS2Received(MqttMessage message) {
        // MQTT3: no reasons code
        return true;
    }

    @Override
    public ProtocolResponse respondPubRecMsg(MqttMessage message, boolean packetIdNotFound) {
        if (packetIdNotFound) {
            return goAway(getLocal(ProtocolViolation.class)
                .statement("MQTT3-4.3.3-1")
                .clientInfo(clientInfo));
        }
        int packetId = ((MqttMessageIdVariableHeader) message.variableHeader()).messageId();
        return response(MQTT3MessageBuilders.pubRel().packetId(packetId).build());
    }

    @Override
    public int clientReceiveMaximum() {
        // In MQTT3 there is no flow control, we assume it the max packet id numbers
        return 65535;
    }

    @Override
    public ProtocolResponse onKick(ClientInfo kicker) {
        return goAwayNow(getLocal(Kicked.class).kicker(kicker).clientInfo(clientInfo));
    }

    @Override
    public MqttPublishMessage buildMqttPubMessage(int packetId, MQTTSessionHandler.SubMessage message, boolean isDup) {
        return MQTT3MessageBuilders.pub()
            .messageId(packetId)
            .topicName(message.topic())
            .qos(message.qos())
            .retained(message.isRetain())
            .dup(isDup)
            .payload(message.message().getPayload())
            .build();
    }

    @Override
    public ProtocolResponse respondReceivingMaximumExceeded(MqttPublishMessage message) {
        return responseNothing(getLocal(Discard.class)
            .rateLimit(settings.maxMsgPerSec)
            .reqId(message.variableHeader().packetId())
            .qos(QoS.forNumber(message.fixedHeader().qosLevel().value()))
            .topic(message.variableHeader().topicName())
            .size(message.payload().readableBytes())
            .clientInfo(clientInfo));
    }

    @Override
    public ProtocolResponse respondPubRateExceeded(MqttPublishMessage message) {
        return responseNothing(getLocal(Discard.class)
            .rateLimit(settings.maxMsgPerSec)
            .reqId(message.variableHeader().packetId())
            .qos(QoS.forNumber(message.fixedHeader().qosLevel().value()))
            .topic(message.variableHeader().topicName())
            .size(message.payload().readableBytes())
            .clientInfo(clientInfo));
    }

    @Override
    public ProtocolResponse validatePubMessage(MqttPublishMessage message) {
        String topic = message.variableHeader().topicName();
        if (!UTF8Util.isWellFormed(topic, SANITY_CHECK)) {
            return goAway(getLocal(MalformedTopic.class)
                .topic(topic)
                .clientInfo(clientInfo));
        }
        if (!TopicUtil.isValidTopic(topic,
            settings.maxTopicLevelLength,
            settings.maxTopicLevels,
            settings.maxTopicLength)) {
            return goAway(getLocal(InvalidTopic.class)
                .topic(topic)
                .clientInfo(clientInfo));
        }
        if (message.fixedHeader().qosLevel() == MqttQoS.AT_MOST_ONCE && message.fixedHeader().isDup()) {
            // ignore the QoS = 0 Dup = 1 messages according to [MQTT-3.3.1-2]
            return goAway(getLocal(ProtocolViolation.class).statement("MQTT3-3.3.1-2").clientInfo(clientInfo));
        }
        if (message.fixedHeader().qosLevel().value() > settings.maxQoS.getNumber()) {
            return goAway(getLocal(ProtocolViolation.class)
                .statement(message.fixedHeader().qosLevel().value() + " is disabled")
                .clientInfo(clientInfo));
        }
        return null;
    }

    @Override
    public String getTopic(MqttPublishMessage message) {
        return message.variableHeader().topicName();
    }

    @Override
    public Message buildDistMessage(long msgId, MqttPublishMessage message, long nowMillis) {
        return toMessage(msgId, message, nowMillis);
    }

    @Override
    public ProtocolResponse onQoS0DistDenied(String topic, Message distMessage, CheckResult result) {
        return goAway(getLocal(NoPubPermission.class)
            .topic(topic)
            .qos(QoS.AT_MOST_ONCE)
            .retain(distMessage.getIsRetain())
            .clientInfo(clientInfo));
    }

    @Override
    public ProtocolResponse onQoS0PubHandled(PubResult result, MqttPublishMessage message, UserProperties userProps) {
        return responseNothing();
    }

    @Override
    public ProtocolResponse onQoS1DistDenied(String topic, int packetId, Message distMessage, CheckResult result) {
        return goAway(getLocal(NoPubPermission.class)
            .qos(AT_LEAST_ONCE)
            .topic(topic)
            .retain(distMessage.getIsRetain())
            .clientInfo(clientInfo));
    }

    @Override
    public ProtocolResponse onQoS1PubHandled(PubResult result, MqttPublishMessage message, UserProperties userProps) {
        if (result.distResult() == DistResult.BACK_PRESSURE_REJECTED ||
            result.retainResult() == RetainReply.Result.BACK_PRESSURE_REJECTED) {
            return responseNothing();
        } else {
            if (settings.debugMode) {
                return response(MqttMessageBuilders.pubAck()
                        .packetId(message.variableHeader().packetId())
                        .build(),
                    getLocal(QoS1PubAcked.class)
                        .reqId(message.variableHeader().packetId())
                        .isDup(message.fixedHeader().isDup())
                        .topic(message.variableHeader().topicName())
                        .size(message.payload().readableBytes())
                        .clientInfo(clientInfo));
            } else {
                return response(MqttMessageBuilders.pubAck()
                    .packetId(message.variableHeader().packetId())
                    .build());
            }
        }
    }

    @Override
    public ProtocolResponse respondQoS2PacketInUse(MqttPublishMessage message) {
        return goAway(getLocal(ProtocolViolation.class).statement("MQTT3-2.3.1-4").clientInfo(clientInfo));
    }

    @Override
    public ProtocolResponse onQoS2DistDenied(String topic, int packetId, Message distMessage, CheckResult result) {
        return goAway(getLocal(NoPubPermission.class)
            .topic(topic)
            .qos(QoS.EXACTLY_ONCE)
            .retain(distMessage.getIsRetain())
            .clientInfo(clientInfo));
    }

    @Override
    public ProtocolResponse onQoS2PubHandled(PubResult result, MqttPublishMessage message, UserProperties userProps) {
        if (result.distResult() == DistResult.BACK_PRESSURE_REJECTED
            || result.retainResult() == RetainReply.Result.BACK_PRESSURE_REJECTED) {
            return responseNothing();
        } else {
            if (settings.debugMode) {
                return response(MQTT3MessageBuilders.pubRec()
                        .packetId(message.variableHeader().packetId())
                        .build(),
                    getLocal(QoS2PubReced.class)
                        .reqId(message.variableHeader().packetId())
                        .isDup(message.fixedHeader().isDup())
                        .topic(message.variableHeader().topicName())
                        .size(message.payload().readableBytes())
                        .clientInfo(clientInfo));
            } else {
                return response(MQTT3MessageBuilders.pubRec()
                    .packetId(message.variableHeader().packetId())
                    .build());
            }
        }
    }

    @Override
    public ProtocolResponse onIdleTimeout(int keepAliveTimeSeconds) {
        return goAwayNow(getLocal(Idle.class)
            .keepAliveTimeSeconds(keepAliveTimeSeconds)
            .clientInfo(clientInfo));
    }
}
