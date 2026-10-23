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
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class ParentChildContextExpander implements ContextExpander {
    private final StoredChunkLookup lookup;
    private final KnowledgeChunkMapper chunks;

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<ContextUnit> expand(List<RetrievedChunk> hits) {
        var valid = lookup.valid(hits);
        var ids = valid.stream().map(h -> h.row().getParentId()).filter(Objects::nonNull).distinct().toList();
        Map<String, KnowledgeChunkDO> parents = ids.isEmpty() ? Map.of() : chunks.selectByIds(ids).stream()
                .collect(Collectors.toMap(KnowledgeChunkDO::getId, p -> p));
        Map<String, ContextUnit> result = new LinkedHashMap<>();
        for (var h : valid) {
            var row = h.row();
            if ("PARENT".equals(row.getChunkType())) continue; // Never trust accidental parent vector entries.
            if (!"CHILD".equals(row.getChunkType())) {
                result.putIfAbsent(row.getId(), ContextUnit.original(h.retrieved()));
                continue;
            }
            var parent = row.getParentId() == null ? null : parents.get(row.getParentId());
            if (parent == null) {
                result.put(row.getId(), new ContextUnit(h.retrieved(), List.of(row.getId()),
                        "FALLBACK_MISSING_PARENT", null, row.getChunkIndex(), row.getChunkIndex()));
                continue;
            }
            if (!"PARENT".equals(parent.getChunkType()) || !Objects.equals(parent.getDocId(), row.getDocId())
                    || !Objects.equals(parent.getKbId(), row.getKbId()) || !Integer.valueOf(1).equals(parent.getEnabled())) continue;
            var previous = result.get(parent.getId());
            List<String> sources = new ArrayList<>(previous == null ? List.of() : previous.hitChunkIds());
            if (!sources.contains(row.getId())) sources.add(row.getId());
            var context = previous == null ? h.retrieved().toBuilder().id(parent.getId())
                    .text(parent.getContent()).chunkIndex(parent.getChunkIndex()).build() : previous.chunk();
            result.put(parent.getId(), new ContextUnit(context, sources, "EXPANDED_PARENT", null,
                    parent.getChunkIndex(), parent.getChunkIndex()));
        }
        // Non-KB channels do not have stored chunk identities.
        hits.stream().filter(h -> h.getCollectionName() == null).forEach(h ->
                result.putIfAbsent("external:" + h.getId(), ContextUnit.original(h)));
        return ContextUnit.orderByHits(List.copyOf(result.values()), hits);
    }
}
