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
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
class EvalIngestionControllerTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(KnowledgeDocumentMapper.class, () -> mock(KnowledgeDocumentMapper.class))
            .withBean(KnowledgeDocumentService.class, () -> mock(KnowledgeDocumentService.class))
            .withUserConfiguration(EvalIngestionController.class);
    @Test void requiresBothExplicitEvaluationSwitches() {
        context.run(c -> assertThat(c).doesNotHaveBean(EvalIngestionController.class));
        context.withPropertyValues("ragent.eval.enabled=true").run(c -> assertThat(c).doesNotHaveBean(EvalIngestionController.class));
        context.withPropertyValues("ragent.eval.enabled=true", "ragent.eval.ingestion-enabled=true")
                .run(c -> assertThat(c).hasSingleBean(EvalIngestionController.class));
    }
}
