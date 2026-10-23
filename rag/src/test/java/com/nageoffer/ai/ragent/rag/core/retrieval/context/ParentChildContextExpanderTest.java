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
import com.nageoffer.ai.ragent.knowledge.dao.entity.*;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ParentChildContextExpanderTest {
    private StoredChunkLookup.Hit hit(String id) {
        var row = KnowledgeChunkDO.builder().id(id).docId("d").kbId("kb").parentId("p")
                .chunkType("CHILD").content(id).chunkIndex(0).enabled(1).build();
        return new StoredChunkLookup.Hit(RetrievedChunk.builder().id(id).collectionName("col").text(id).build(), row,
                KnowledgeDocumentDO.builder().id("d").enabled(1).build());
    }
    @Test void deduplicatesParentsAndPreservesBestHitOrderIncludingExternalSources() {
        var lookup = mock(StoredChunkLookup.class);
        var mapper = mock(KnowledgeChunkMapper.class);
        var a = hit("a"); var b = hit("b");
        var external = RetrievedChunk.builder().id("web").text("external").build();
        when(lookup.valid(any())).thenReturn(List.of(a, b));
        when(mapper.selectByIds(any())).thenReturn(List.of(KnowledgeChunkDO.builder().id("p").docId("d").kbId("kb")
                .chunkType("PARENT").content("complete parent").enabled(1).chunkIndex(0).build()));
        var result = new ParentChildContextExpander(lookup, mapper).expand(List.of(external, a.retrieved(), b.retrieved()));
        assertEquals(2, result.size()); assertEquals("web", result.get(0).chunk().getId());
        assertEquals("p", result.get(1).chunk().getId());
        assertEquals(List.of("a", "b"), result.get(1).hitChunkIds());
        assertEquals("complete parent", result.get(1).chunk().getText());
        assertEquals("a", a.retrieved().getText());
    }
    @Test void missingParentFallsBackButCrossDocumentOrDisabledParentCannotExpand() {
        var lookup = mock(StoredChunkLookup.class); var mapper = mock(KnowledgeChunkMapper.class);
        var a = hit("a"); when(lookup.valid(any())).thenReturn(List.of(a));
        var expander = new ParentChildContextExpander(lookup, mapper);
        when(mapper.selectByIds(any())).thenReturn(List.of());
        assertEquals("FALLBACK_MISSING_PARENT", expander.expand(List.of(a.retrieved())).get(0).status());
        a.row().setParentId(null);
        assertEquals("FALLBACK_MISSING_PARENT", expander.expand(List.of(a.retrieved())).get(0).status());
        a.row().setParentId("p");
        var parent = KnowledgeChunkDO.builder().id("p").docId("other").kbId("kb").chunkType("PARENT").enabled(1).build();
        when(mapper.selectByIds(any())).thenReturn(List.of(parent));
        assertTrue(expander.expand(List.of(a.retrieved())).isEmpty());
        parent.setDocId("d"); parent.setEnabled(0);
        assertTrue(expander.expand(List.of(a.retrieved())).isEmpty());
    }
}
