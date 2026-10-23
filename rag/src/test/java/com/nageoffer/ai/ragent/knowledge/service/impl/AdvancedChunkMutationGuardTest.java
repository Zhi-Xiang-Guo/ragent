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

package com.nageoffer.ai.ragent.knowledge.service.impl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.*;
import com.nageoffer.ai.ragent.knowledge.support.IngestionSpecCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AdvancedChunkMutationGuardTest {
    @Test void rejectsEverySingleChunkMutationEvenAfterConfigWasChangedBackToStandard() {
        var chunks = mock(KnowledgeChunkMapper.class); var docs = mock(KnowledgeDocumentMapper.class);
        var codec = new IngestionSpecCodec(new ObjectMapper());
        var doc = KnowledgeDocumentDO.builder().id("d").kbId("kb").enabled(1)
                .ingestionSpec(codec.normalize("{\"indexingMode\":\"SENTENCE_WINDOW\"}")).build();
        when(docs.selectById("d")).thenReturn(doc);
        when(chunks.selectCount(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(1L);
        var service = new KnowledgeChunkServiceImpl(chunks,docs,null,null,null,null,null,null,null,codec);
        List<Executable> operations = List.of(() -> service.create("d",null), () -> service.update("d","c",null),
                () -> service.delete("d","c"), () -> service.enableChunk("d","c",false),
                () -> service.batchToggleEnabled("d",null,false));
        for (Executable operation : operations) assertThrows(ClientException.class,operation);
        doc.setIngestionSpec(null);
        for (Executable operation : operations) assertThrows(ClientException.class,operation);
        verify(chunks, never()).deleteById(any(java.io.Serializable.class));
        verify(chunks, never()).updateById(any(com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO.class));
    }
}
