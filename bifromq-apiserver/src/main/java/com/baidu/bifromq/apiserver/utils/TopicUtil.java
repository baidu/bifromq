package com.baidu.bifromq.apiserver.utils;

import com.baidu.bifromq.mqtt.utils.MQTTUtf8Util;
import com.baidu.bifromq.plugin.settingprovider.ISettingProvider;
import com.baidu.bifromq.sysprops.BifroMQSysProp;

import static com.baidu.bifromq.mqtt.utils.TopicUtil.isValidTopicFilter;
import static com.baidu.bifromq.plugin.settingprovider.Setting.MaxTopicLength;
import static com.baidu.bifromq.plugin.settingprovider.Setting.MaxTopicLevelLength;
import static com.baidu.bifromq.plugin.settingprovider.Setting.MaxTopicLevels;

public class TopicUtil {
    private static final int INBOX_GROUPS = 50;
    public static boolean checkTopicFilter(String topic, String tenantId, ISettingProvider settingProvider) {
        int maxTopicLevelLength = settingProvider.provide(MaxTopicLevelLength, tenantId);
        int maxTopicLevels = settingProvider.provide(MaxTopicLevels, tenantId);
        int maxTopicLength = settingProvider.provide(MaxTopicLength, tenantId);
        return MQTTUtf8Util.isWellFormed(topic, BifroMQSysProp.MQTT_UTF8_SANITY_CHECK.get()) &&
                isValidTopicFilter(topic, maxTopicLevelLength, maxTopicLevels, maxTopicLength);
    }

    public static String generateDeliverKey(String inboxId) {
        return String.valueOf(groupIdx(inboxId));
    }

    private static int groupIdx(String inboxId) {
        int idx = inboxId.hashCode() % INBOX_GROUPS;
        if (idx < 0) {
            idx = (idx + INBOX_GROUPS) % INBOX_GROUPS;
        }
        return idx;
    }
}
