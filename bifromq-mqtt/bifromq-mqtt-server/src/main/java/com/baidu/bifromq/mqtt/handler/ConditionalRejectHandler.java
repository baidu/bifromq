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

package com.baidu.bifromq.mqtt.handler;

import static com.baidu.bifromq.plugin.eventcollector.ThreadLocalEventPool.getLocal;

import com.baidu.bifromq.mqtt.handler.condition.Condition;
import com.baidu.bifromq.plugin.eventcollector.IEventCollector;
import com.baidu.bifromq.plugin.eventcollector.mqttbroker.channelclosed.ChannelError;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ConditionalRejectHandler extends ChannelInboundHandlerAdapter {
    public static final String NAME = "ConditionalRejectHandler";
    private final Set<Condition> rejectConditions;
    private final IEventCollector eventCollector;

    public ConditionalRejectHandler(Set<Condition> rejectConditions, IEventCollector eventCollector) {
        this.rejectConditions = rejectConditions;
        this.eventCollector = eventCollector;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        for (Condition cond : rejectConditions) {
            if (cond.meet()) {
                log.debug("Reject connection due to {}: remote={}", cond, ctx.channel().remoteAddress());
                ctx.close();
                eventCollector.report(getLocal(ChannelError.class)
                    .peerAddress(ChannelAttrs.socketAddress(ctx.channel()))
                    .cause(new RuntimeException("Reject connection due to " + cond)));
                return;
            }
        }
        ctx.fireChannelRead(msg);
    }
}
