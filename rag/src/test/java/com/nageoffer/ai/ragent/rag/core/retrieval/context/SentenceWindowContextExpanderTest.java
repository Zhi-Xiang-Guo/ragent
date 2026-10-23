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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.knowledge.dao.entity.*;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.support.IngestionSpecCodec;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class SentenceWindowContextExpanderTest {
    private KnowledgeChunkDO row(int i, String section) {
        return KnowledgeChunkDO.builder().id(section + i).docId("d").kbId("kb").sectionId(section)
                .chunkType("SENTENCE").content(section + i).chunkIndex(i).enabled(1).build();
    }
    @Test void mergesBridgedWindowsWithoutCrossingSectionsAndKeepsExternalRank() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(), ""), KnowledgeChunkDO.class);
        var lookup = mock(StoredChunkLookup.class); var mapper = mock(KnowledgeChunkMapper.class);
        var codec = new IngestionSpecCodec(new ObjectMapper());
        var doc = KnowledgeDocumentDO.builder().id("d").ingestionSpec(codec.normalize("{\"indexingMode\":\"SENTENCE_WINDOW\",\"windowRadius\":2}")).build();
        List<StoredChunkLookup.Hit> valid = new ArrayList<>();
        for (int i : List.of(0, 6, 3)) {
            var row = row(i,"s"); valid.add(new StoredChunkLookup.Hit(RetrievedChunk.builder().id(row.getId())
                    .collectionName("col").text(row.getContent()).build(), row, doc));
        }
        var other = row(9,"next"); valid.add(new StoredChunkLookup.Hit(RetrievedChunk.builder().id(other.getId())
                .collectionName("col").text(other.getContent()).build(), other, doc));
        when(lookup.valid(any())).thenReturn(valid);
        List<KnowledgeChunkDO> neighbors = new ArrayList<>();
        for (int i=0;i<9;i++) neighbors.add(row(i,"s"));
        neighbors.add(other);
        when(mapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(neighbors);
        var external = RetrievedChunk.builder().id("web").text("external").build();
        List<RetrievedChunk> hits = new ArrayList<>(List.of(external)); valid.forEach(h -> hits.add(h.retrieved()));
        var result = new SentenceWindowContextExpander(lookup, mapper, codec).expand(hits);
        assertEquals(3, result.size()); assertEquals("web", result.get(0).chunk().getId());
        assertEquals(0, result.get(1).startIndex()); assertEquals(8, result.get(1).endIndex());
        assertEquals(Set.of("s0","s3","s6"), new HashSet<>(result.get(1).hitChunkIds()));
        assertFalse(result.get(1).chunk().getText().contains("next"));
        assertEquals("next", result.get(2).sectionId());
        verify(mapper, times(1)).selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }
}
