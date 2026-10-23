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

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.support.IngestionSpecCodec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class SentenceWindowContextExpander implements ContextExpander {
    private final StoredChunkLookup lookup;
    private final KnowledgeChunkMapper chunks;
    private final IngestionSpecCodec specCodec;
    private static final class Window {
        StoredChunkLookup.Hit best;
        int start;
        int end;
        List<String> sources = new ArrayList<>();
        Window(StoredChunkLookup.Hit hit, int radius) {
            best = hit; start = Math.max(0, hit.row().getChunkIndex() - radius);
            end = hit.row().getChunkIndex() + radius; sources.add(hit.row().getId());
        }
        boolean overlaps(Window other) {
            return Objects.equals(best.row().getDocId(), other.best.row().getDocId())
                    && Objects.equals(best.row().getSectionId(), other.best.row().getSectionId())
                    && start <= other.end && other.start <= end;
        }
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<ContextUnit> expand(List<RetrievedChunk> hits) {
        var valid = lookup.valid(hits);
        List<Object> ordered = new ArrayList<>();
        for (var hit : valid) {
            var row = hit.row();
            if (!"SENTENCE".equals(row.getChunkType())) { ordered.add(ContextUnit.original(hit.retrieved())); continue; }
            if (row.getSectionId() == null || row.getChunkIndex() == null) {
                ordered.add(new ContextUnit(hit.retrieved(), List.of(row.getId()), "FALLBACK_MISSING_SECTION",
                        null, row.getChunkIndex(), row.getChunkIndex()));
                continue;
            }
            Window merged = new Window(hit, specCodec.read(hit.document().getIngestionSpec()).indexing().windowRadius());
            int first = -1;
            for (int i = 0; i < ordered.size();) {
                if (ordered.get(i) instanceof Window previous && merged.overlaps(previous)) {
                    if (first < 0 || i < first) { first = i; merged.best = previous.best; }
                    merged.start = Math.min(merged.start, previous.start);
                    merged.end = Math.max(merged.end, previous.end);
                    merged.sources.addAll(previous.sources);
                    ordered.remove(i);
                    i = 0; // A bridge can connect multiple earlier windows.
                } else i++;
            }
            ordered.add(first < 0 ? ordered.size() : Math.min(first, ordered.size()), merged);
        }
        // One batched bounded query for all windows, not one query per sentence.
        List<Window> windows = ordered.stream().filter(Window.class::isInstance).map(Window.class::cast).toList();
        List<KnowledgeChunkDO> neighbors = List.of();
        if (!windows.isEmpty()) {
            var query = new LambdaQueryWrapper<KnowledgeChunkDO>()
                    .eq(KnowledgeChunkDO::getEnabled, 1).eq(KnowledgeChunkDO::getChunkType, "SENTENCE");
            query.and(scope -> {
                for (Window w : windows) scope.or(one -> one.eq(KnowledgeChunkDO::getDocId, w.best.row().getDocId())
                        .eq(KnowledgeChunkDO::getKbId, w.best.row().getKbId())
                        .eq(KnowledgeChunkDO::getSectionId, w.best.row().getSectionId())
                        .between(KnowledgeChunkDO::getChunkIndex, w.start, w.end));
            });
            neighbors = chunks.selectList(query);
        }
        List<ContextUnit> result = new ArrayList<>();
        for (Object item : ordered) {
            if (item instanceof ContextUnit original) { result.add(original); continue; }
            Window w = (Window) item;
            var selected = neighbors.stream().filter(n -> Objects.equals(n.getDocId(), w.best.row().getDocId())
                    && Objects.equals(n.getKbId(), w.best.row().getKbId())
                    && Objects.equals(n.getSectionId(), w.best.row().getSectionId())
                    && n.getChunkIndex() >= w.start && n.getChunkIndex() <= w.end)
                    .sorted(Comparator.comparing(KnowledgeChunkDO::getChunkIndex)).toList();
            if (selected.isEmpty()) { result.add(ContextUnit.original(w.best.retrieved())); continue; }
            String text = selected.stream().map(KnowledgeChunkDO::getContent).collect(java.util.stream.Collectors.joining("\n"));
            int start = selected.get(0).getChunkIndex(), end = selected.get(selected.size() - 1).getChunkIndex();
            var context = w.best.retrieved().toBuilder().id("window:" + w.best.row().getDocId() + ":" + start)
                    .text(text).chunkIndex(start).build();
            result.add(new ContextUnit(context, w.sources.stream().distinct().toList(), "EXPANDED_WINDOW",
                    w.best.row().getSectionId(), start, end));
        }
        hits.stream().filter(h -> h.getCollectionName() == null).forEach(h -> result.add(ContextUnit.original(h)));
        return ContextUnit.orderByHits(result, hits);
    }
}
