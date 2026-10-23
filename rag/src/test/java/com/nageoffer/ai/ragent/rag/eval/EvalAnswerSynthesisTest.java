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
import com.nageoffer.ai.ragent.framework.convention.*;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.*;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.rewrite.*;
import com.nageoffer.ai.ragent.rag.dto.*;
import com.nageoffer.ai.ragent.rag.service.KnowledgeSearchFacade;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class EvalAnswerSynthesisTest {
    @Test void answersFromExactlyOneRetrievalAndReturnsThatProductionPrompt() {
        var rewrite = mock(QueryRewriteService.class); var resolver = mock(IntentResolver.class);
        var engine = mock(RetrievalEngine.class); var facade = mock(KnowledgeSearchFacade.class);
        var llm = mock(LLMService.class);
        var result = new RewriteResult("Q",List.of("Q"));
        var intents = List.of(new SubQuestionIntent("Q",List.of()));
        var context = RetrievalContext.builder().kbContext("expanded evidence").build();
        var request = ChatRequest.builder().messages(List.of(ChatMessage.user("Q"))).build();
        when(rewrite.rewriteWithSplit("Q",List.of())).thenReturn(result);
        when(resolver.resolve(result)).thenReturn(intents);
        when(engine.retrieve(intents,true)).thenReturn(context);
        when(facade.buildAnswerRequest(result,intents,context)).thenReturn(request);
        when(facade.answerContext(context)).thenReturn("expanded evidence");
        when(llm.chat(request)).thenReturn("answer");
        var controller = new EvalController(rewrite,resolver,engine,mock(KnowledgeChunkMapper.class),
                mock(KnowledgeDocumentMapper.class),facade,llm);
        var response = controller.chat("Q",true,true).getData();
        assertEquals("answer",response.getAnswer()); assertEquals("SUCCESS",response.getAnswerStatus());
        assertEquals(request.getMessages(),response.getAnswerMessages());
        assertEquals("expanded evidence",response.getFinalContext());
        verify(engine,times(1)).retrieve(intents,true); verify(engine,never()).retrieve(any());
        verify(llm,times(1)).chat(request);
        when(llm.chat(request)).thenThrow(new IllegalStateException("private-provider-detail"));
        var failed = controller.chat("Q",true,true).getData();
        assertEquals("ERROR",failed.getAnswerStatus()); assertNull(failed.getAnswer());
        assertFalse(new ObjectMapper().valueToTree(failed).toString().contains("private-provider-detail"));
    }
}
