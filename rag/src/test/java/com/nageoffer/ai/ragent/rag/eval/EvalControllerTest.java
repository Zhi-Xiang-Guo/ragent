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

package com.nageoffer.ai.ragent.rag.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.dto.*;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EvalControllerTest {
    @Test
    void addsRankingsWithoutChangingLegacyArraysAndSerializesNullScores() throws Exception {
        var rewrite = mock(QueryRewriteService.class);
        var resolver = mock(IntentResolver.class);
        var engine = mock(RetrievalEngine.class);
        var chunks = mock(KnowledgeChunkMapper.class);
        var docs = mock(KnowledgeDocumentMapper.class);
        var a1 = chunk("a1", "doc-a");
        var b1 = chunk("b1", "doc-b");
        var a2 = chunk("a2", "doc-a");
        var grouped = new LinkedHashMap<String, List<RetrievedChunk>>();
        grouped.put("A", List.of(a1, a2));
        grouped.put("B", List.of(b1, a1));
        var ordered = List.of(new SubQuestionRetrievalResult("问题", "SUCCESS", RankedChunk.snapshot(List.of(a1, b1, a2))));
        when(rewrite.rewriteWithSplit("问题", List.of())).thenReturn(new RewriteResult("问题", List.of("问题")));
        when(resolver.resolve(any())).thenReturn(List.of(new SubQuestionIntent("问题", List.of())));
        when(engine.retrieve(any())).thenReturn(RetrievalContext.builder().kbContext("资料")
                .intentChunks(grouped).subQuestionResults(ordered).build());
        when(chunks.selectByIds(any())).thenReturn(List.of(
                KnowledgeChunkDO.builder().id("a1").docId("doc-a").build(),
                KnowledgeChunkDO.builder().id("a2").docId("doc-a").build(),
                KnowledgeChunkDO.builder().id("b1").docId("doc-b").build()));
        when(docs.selectByIds(any())).thenReturn(List.of(
                KnowledgeDocumentDO.builder().id("doc-a").docName("A.md").build(),
                KnowledgeDocumentDO.builder().id("doc-b").docName("B.pdf").build()));
        var response = new EvalController(rewrite, resolver, engine, chunks, docs).chat("问题").getData();
        assertEquals(List.of("a1", "a2", "b1"), response.getRetrievedChunkIds());
        assertEquals(List.of("a1", "a2", "b1"), response.getRetrievedContexts());
        assertEquals(List.of("A", "A", "B"), response.getRetrievedContextDocIds());
        assertEquals(List.of("A", "B"), response.getRetrievedDocIds());
        assertEquals(ordered, response.getSubQuestionResults());
        assertTrue(response.isHasKb());
        assertEquals(List.of("问题"), response.getSubIntents());
        assertNull(response.getIntentLeafIds().get(0));
        var json = new ObjectMapper().valueToTree(response);
        assertEquals("b1", json.at("/subQuestionResults/0/rankedChunks/1/chunkId").asText());
        assertTrue(json.at("/subQuestionResults/0/rankedChunks/0/rerankScore").isNull());
        verify(engine, times(1)).retrieve(any());
    }

    @Test
    void snapshotDeduplicatesPerCollectionAndKeepsMissingIds() {
        var a = chunk("same", "a");
        var b = chunk("same", "b");
        b.setCollectionName("other");
        var anonymous = chunk(null, null);
        var ranked = RankedChunk.snapshot(List.of(a, a, b, anonymous, anonymous));
        assertEquals(3, ranked.size());
        assertEquals(List.of(1, 2, 3), ranked.stream().map(RankedChunk::rank).toList());
        assertNull(ranked.get(2).chunkId());
        assertThrows(UnsupportedOperationException.class, () -> ranked.clear());
    }

    private RetrievedChunk chunk(String id, String docId) {
        return RetrievedChunk.builder().id(id).docId(docId).collectionName("kb").text(id).build();
    }
}
