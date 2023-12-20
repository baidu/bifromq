/*
 * Copyright (c) 2023. The BifroMQ Authors. All Rights Reserved.
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

package com.baidu.bifromq.basecrdt.core.internal;

import com.baidu.bifromq.basecrdt.core.api.DWFlagOperation;
import com.baidu.bifromq.basecrdt.proto.Replacement;
import com.google.protobuf.ByteString;

class DWFlagCoalesceOperation extends CoalesceOperation<IDotSet, DWFlagOperation> {
    DWFlagOperation op;

    DWFlagCoalesceOperation(ByteString replicaId, DWFlagOperation op) {
        super(replicaId);
        coalesce(op);
    }

    @Override
    public void coalesce(DWFlagOperation op) {
        this.op = op;
    }

    @Override
    public Iterable<Replacement> delta(IDotSet current, IEventGenerator eventGenerator) {
        long ver = eventGenerator.nextEvent();
        switch (op.type) {
            case Disable:
                return ProtoUtils.replacements(ProtoUtils.dot(replicaId, ver,
                    ProtoUtils.singleDot(replicaId, ver)), current);
            case Enable:
            default:
                return ProtoUtils.replacements(ProtoUtils.dot(replicaId, ver), current);
        }
    }
}
