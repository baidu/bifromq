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

package com.baidu.bifromq.basekv.raft.exception;

public class ReadIndexException extends RuntimeException {
    public static ReadIndexException commitIndexNotConfirmed() {
        return new CommitIndexNotConfirmedException();
    }

    public static ReadIndexException leaderStepDown() {
        return new LeaderStepDownException();
    }

    public static ReadIndexException noLeader() {
        return new NoLeaderException();
    }

    public static ReadIndexException forwardTimeout() {
        return new ForwardTimeoutException();
    }

    private ReadIndexException(String message) {
        super(message);
    }

    public static class CommitIndexNotConfirmedException extends ReadIndexException {
        private CommitIndexNotConfirmedException() {
            super("Leader has not confirmed the commit index of its term");
        }
    }

    public static class LeaderStepDownException extends ReadIndexException {
        private LeaderStepDownException() {
            super("Leader has been stepped down");
        }
    }

    public static class NoLeaderException extends ReadIndexException {
        private NoLeaderException() {
            super("No leader elected");
        }
    }

    public static class ForwardTimeoutException extends ReadIndexException {
        private ForwardTimeoutException() {
            super("Doesn't receive read index from leader within timeout");
        }
    }
}
