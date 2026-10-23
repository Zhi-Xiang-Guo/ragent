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

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Optional benchmark transport; executes the same production service without the MQ hop. */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ragent.eval", name = {"enabled", "ingestion-enabled"}, havingValue = "true")
public class EvalIngestionController {
    private final KnowledgeDocumentMapper documents;
    private final KnowledgeDocumentService service;

    @PostMapping("/rag/eval/ingest/{docId}")
    public Result<Void> ingest(@PathVariable String docId) {
        int claimed = documents.update(new LambdaUpdateWrapper<KnowledgeDocumentDO>()
                .eq(KnowledgeDocumentDO::getId, docId)
                .eq(KnowledgeDocumentDO::getEnabled, 1)
                .ne(KnowledgeDocumentDO::getStatus, "running")
                .set(KnowledgeDocumentDO::getStatus, "running")
                .set(KnowledgeDocumentDO::getUpdateTime, new java.util.Date()));
        if (claimed != 1) throw new ClientException("Document unavailable or ingestion already running");
        service.executeChunk(docId);
        return Results.success();
    }
}
