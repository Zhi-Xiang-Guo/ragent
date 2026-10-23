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
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StoredChunkLookup {
    private final KnowledgeChunkMapper chunks;
    private final KnowledgeDocumentMapper documents;
    private final KnowledgeBaseMapper bases;
    public record Hit(RetrievedChunk retrieved, KnowledgeChunkDO row, KnowledgeDocumentDO document) { }
    public List<Hit> valid(List<RetrievedChunk> hits) {
        var ids = hits.stream().map(RetrievedChunk::getId).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return List.of();
        var rows = chunks.selectByIds(ids).stream().collect(Collectors.toMap(KnowledgeChunkDO::getId, r -> r));
        var docIds = rows.values().stream().map(KnowledgeChunkDO::getDocId).distinct().toList();
        if (docIds.isEmpty()) return List.of();
        var docs = documents.selectByIds(docIds).stream().collect(Collectors.toMap(KnowledgeDocumentDO::getId, d -> d));
        var kbIds = docs.values().stream().map(KnowledgeDocumentDO::getKbId).distinct().toList();
        if (kbIds.isEmpty()) return List.of();
        var kbs = bases.selectByIds(kbIds).stream().collect(Collectors.toMap(KnowledgeBaseDO::getId, b -> b));
        List<Hit> result = new ArrayList<>();
        for (var hit : hits) {
            var row = rows.get(hit.getId());
            if (row == null || !Integer.valueOf(1).equals(row.getEnabled())) continue;
            var doc = docs.get(row.getDocId());
            if (doc == null || !Integer.valueOf(1).equals(doc.getEnabled()) || !Objects.equals(row.getKbId(), doc.getKbId())) continue;
            var kb = kbs.get(doc.getKbId());
            if (kb == null || !Objects.equals(kb.getCollectionName(), hit.getCollectionName())) continue;
            if (hit.getDocId() != null && !Objects.equals(doc.getId(), hit.getDocId())) continue;
            result.add(new Hit(hit.toBuilder().text(row.getContent()).docId(doc.getId())
                    .docName(doc.getDocName()).chunkIndex(row.getChunkIndex()).build(), row, doc));
        }
        return result;
    }
}
