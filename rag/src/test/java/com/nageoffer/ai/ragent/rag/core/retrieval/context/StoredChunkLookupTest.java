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
import com.nageoffer.ai.ragent.knowledge.dao.mapper.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class StoredChunkLookupTest {
    @Test void trustsStoredTextAndRejectsDisabledOrCrossCollectionHits() {
        var chunks = mock(KnowledgeChunkMapper.class);
        var docs = mock(KnowledgeDocumentMapper.class);
        var bases = mock(KnowledgeBaseMapper.class);
        var row = KnowledgeChunkDO.builder().id("c").docId("d").kbId("kb").content("stored").chunkIndex(1).enabled(1).build();
        var disabled = KnowledgeChunkDO.builder().id("off").docId("d").kbId("kb").enabled(0).build();
        var doc = KnowledgeDocumentDO.builder().id("d").kbId("kb").docName("document").enabled(1).build();
        when(chunks.selectByIds(any())).thenReturn(List.of(row, disabled));
        when(docs.selectByIds(any())).thenReturn(List.of(doc));
        when(bases.selectByIds(any())).thenReturn(List.of(KnowledgeBaseDO.builder().id("kb").collectionName("collection").build()));
        var hit = RetrievedChunk.builder().id("c").docId("d").collectionName("collection").text("untrusted vector text").build();
        var lookup = new StoredChunkLookup(chunks, docs, bases);
        var result = lookup.valid(List.of(hit, hit.toBuilder().collectionName("other").build(),
                hit.toBuilder().docId("other").build(), hit.toBuilder().id("off").build(), hit.toBuilder().id("deleted").build()));
        assertEquals(1, result.size());
        assertEquals("stored", result.get(0).retrieved().getText());
        assertEquals("untrusted vector text", hit.getText());
        doc.setEnabled(0);
        assertTrue(lookup.valid(List.of(hit)).isEmpty());
        when(docs.selectByIds(any())).thenReturn(List.of());
        assertTrue(lookup.valid(List.of(hit)).isEmpty());
    }
}
