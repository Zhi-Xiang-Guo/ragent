/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.core.retrieval.context;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class ContextAssemblyTest {
    @Test void retainsEvidenceAndValidWrapperWithinCodePointBudget() {
        var hit = RetrievedChunk.builder().id("c").text("关键证据").build();
        var expanded = hit.toBuilder().id("p").text("😀".repeat(80) + "关键证据" + "尾".repeat(80)).build();
        ContextExpander expander = hits -> List.of(new ContextUnit(expanded, List.of("c"), "EXPANDED", null, 0, 0));
        var result = ContextAssembly.assemble(List.of(hit), List.of(expander),
                chunks -> "<doc>" + chunks.get(0).getText() + "</doc>", 50);
        assertTrue(result.text().contains("关键证据"));
        assertTrue(result.text().endsWith("</doc>"));
        assertTrue(result.text().codePointCount(0, result.text().length()) <= 50);
        assertEquals("关键证据", hit.getText());
        assertTrue(result.units().get(0).status().contains("TRUNCATED"));
    }
    @Test void discardsInvalidHitsReturnedAsEmptyExpansion() {
        var hit = RetrievedChunk.builder().id("stale").text("secret").build();
        var result = ContextAssembly.assemble(List.of(hit), List.of(h -> List.of()), h -> "secret", 8192);
        assertEquals("", result.text());
        assertTrue(result.units().isEmpty());
    }
    @Test void contextUnitsExcludeHitsBeyondTheRenderedTopK() {
        var a = RetrievedChunk.builder().id("a").text("first").build();
        var b = RetrievedChunk.builder().id("b").text("second").build();
        var result = ContextAssembly.assemble(List.of(a, b), List.of(),
                chunks -> chunks.stream().map(RetrievedChunk::getText).collect(java.util.stream.Collectors.joining()), 100, 1);
        assertEquals("first", result.text());
        assertEquals(1, result.units().size());
        assertEquals(List.of("a"), result.units().get(0).hitChunkIds());
    }
}
