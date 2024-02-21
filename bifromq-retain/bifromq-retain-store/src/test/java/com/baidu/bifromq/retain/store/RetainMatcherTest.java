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

package com.baidu.bifromq.retain.store;

import static com.baidu.bifromq.retain.store.RetainMatcher.MatchResult.MATCHED_AND_CONTINUE;
import static com.baidu.bifromq.retain.store.RetainMatcher.MatchResult.MATCHED_AND_STOP;
import static com.baidu.bifromq.retain.store.RetainMatcher.MatchResult.MISMATCH_AND_CONTINUE;
import static com.baidu.bifromq.retain.store.RetainMatcher.MatchResult.MISMATCH_AND_STOP;
import static com.baidu.bifromq.retain.store.RetainMatcher.match;
import static com.baidu.bifromq.retain.utils.TopicUtil.parse;
import static org.testng.Assert.assertEquals;

import org.testng.annotations.Test;

public class RetainMatcherTest {
    @Test
    public void matches() {
        assertEquals(matches("/", "/"), MATCHED_AND_STOP);
        assertEquals(matches("/", "+/+"), MATCHED_AND_CONTINUE);
        assertEquals(matches("/", "+/#"), MATCHED_AND_CONTINUE);
        assertEquals(matches("/", "#"), MATCHED_AND_CONTINUE);
        assertEquals(matches("//", "//"), MATCHED_AND_STOP);
        assertEquals(matches("//", "#"), MATCHED_AND_CONTINUE);
        assertEquals(matches("//", "+/#"), MATCHED_AND_CONTINUE);
        assertEquals(matches("a", "a"), MATCHED_AND_STOP);
        assertEquals(matches("a", "a/#"), MATCHED_AND_CONTINUE);
        assertEquals(matches("a", "#"), MATCHED_AND_CONTINUE);
        assertEquals(matches("a", "/#"), MATCHED_AND_CONTINUE);
        assertEquals(matches("a", "+"), MATCHED_AND_CONTINUE);
        assertEquals(matches("a/", "#"), MATCHED_AND_CONTINUE);
        assertEquals(matches("a/", "+/#"), MATCHED_AND_CONTINUE);
        assertEquals(matches("a/", "a/"), MATCHED_AND_STOP);
        assertEquals(matches("/a", "/a"), MATCHED_AND_STOP);
        assertEquals(matches("/a", "/a/#"), MATCHED_AND_CONTINUE);
        assertEquals(matches("/a", "/+"), MATCHED_AND_CONTINUE);
        assertEquals(matches("/a/b/c", "/+/#"), MATCHED_AND_CONTINUE);
        assertEquals(matches("/a/b/c", "/#"), MATCHED_AND_CONTINUE);
        assertEquals(matches("/a/b/c", "#"), MATCHED_AND_CONTINUE);
        assertEquals(matches("/a/b/c", "/a/b/c/#"), MATCHED_AND_CONTINUE);
        assertEquals(matches("/a/b/c", "/a/b/#"), MATCHED_AND_CONTINUE);
        assertEquals(matches("a/b/c", "#"), MATCHED_AND_CONTINUE);
        assertEquals(matches("a/b/c", "a/#"), MATCHED_AND_CONTINUE);
        assertEquals(matches("a/b/c", "b/#"), MISMATCH_AND_CONTINUE);
        assertEquals(matches("a/b/c", "0/#"), MISMATCH_AND_CONTINUE);
        assertEquals(matches("a/b/c", "a/c/#"), MISMATCH_AND_CONTINUE);
        assertEquals(matches("a/b/c", "a/b/#"), MATCHED_AND_CONTINUE);
        assertEquals(matches("a/b/c", "a/+/c"), MATCHED_AND_CONTINUE);

        assertEquals(matches("a", "/a"), MISMATCH_AND_STOP);
        assertEquals(matches("a", "+/"), MISMATCH_AND_STOP);
        assertEquals(matches("a/", "/a"), MISMATCH_AND_STOP);
        assertEquals(matches("a/b", "+/a"), MISMATCH_AND_STOP);
        assertEquals(matches("a/b", "+/d"), MISMATCH_AND_CONTINUE);

        assertEquals(matches("a/b/c", "a/+/d"), MISMATCH_AND_CONTINUE);
        assertEquals(matches("a/b/c", "+/a/c"), MISMATCH_AND_STOP);
        assertEquals(matches("a/b/c", "+/a/c"), MISMATCH_AND_STOP);
        assertEquals(matches("a/b/c", "+/c/c"), MISMATCH_AND_CONTINUE);
        assertEquals(matches("a/b/c", "a/b/d"), MISMATCH_AND_STOP);
    }

    private RetainMatcher.MatchResult matches(String topic, String topicFilter) {
        return match(parse(topic, false), parse(topicFilter, false));
    }
}
