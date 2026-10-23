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

package com.nageoffer.ai.ragent.core.chunk;

import com.nageoffer.ai.ragent.core.chunk.model.IngestionChunks;
import com.nageoffer.ai.ragent.core.chunk.text.TextSplitter;
import com.nageoffer.ai.ragent.core.ingest.IngestionSpec;

import com.nageoffer.ai.ragent.core.chunk.blockaware.BlockAwareChunkerDispatcher;
import com.nageoffer.ai.ragent.core.chunk.model.Chunk;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkAssembler;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkBudget;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkDraft;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkMetadata;
import com.nageoffer.ai.ragent.core.parser.BlockTextRenderer;
import com.nageoffer.ai.ragent.core.parser.model.Block;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 分块入口：解析产出的 Block 列表 → 成品块
 * <p>
 * 只有两个分支，分支依据是预算而不是用户选的策略：整文档单块，或按 Block 类型分发
 */
@Service
@RequiredArgsConstructor
public class ChunkingService {

    private final BlockAwareChunkerDispatcher blockAwareChunkerDispatcher;

    /**
     * 切分为块列表，序号从 0 单调递增，无可切内容时返回空列表
     *
     * @param budget 分块预算，整文档模式由 {@link ChunkBudget#isWholeDocument()} 表达
     */
    public List<Chunk> chunk(List<Block> blocks, ChunkBudget budget) {
        if (budget.isWholeDocument()) {
            return wholeDocument(blocks);
        }
        return blockAwareChunkerDispatcher.dispatch(blocks, budget);
    }

    public IngestionChunks chunkForIngestion(
            List<Block> blocks, IngestionSpec spec) {
        var options = spec.indexing();
        if (!options.advanced()) return new IngestionChunks(
                chunk(blocks, spec.budget()), List.of());
        List<Chunk> parents = new java.util.ArrayList<>();
        List<Chunk> children = new java.util.ArrayList<>();
        // Reuse the existing structure-aware dispatcher for parent section boundaries.
        for (Chunk source : blockAwareChunkerDispatcher.dispatch(blocks, new ChunkBudget(options.parentMaxChars(), 0,
                spec.budget().rowsPerChunk(), 1), true)) {
            String body = TextSplitter.normalize(source.content());
            if (body.lines().filter(line -> !line.isBlank()).allMatch(line -> line.strip().matches("#{1,6}\\s+.*")))
                continue; // Ancestor-only headings remain in descendant outline metadata.

            Chunk parent = ChunkAssembler.assemble(source.chunkId(), parents.size(), ChunkDraft.of(body,
                    source.metadata().withExtras(java.util.Map.of("chunk_type", "PARENT"))));
            parents.add(parent);
            for (String text : TextSplitter.split(
                    body, options.childMaxChars(), options.childOverlapChars())) {
                children.add(ChunkAssembler.assemble(children.size(), ChunkDraft.of(text,
                        source.metadata().withExtras(java.util.Map.of("chunk_type", "CHILD", "parent_id", parent.chunkId())))));
            }
        }
        return new IngestionChunks(children, parents);
    }

    private List<Chunk> wholeDocument(List<Block> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        String whole = BlockTextRenderer.render(blocks);
        if (!StringUtils.hasText(whole)) {
            return List.of();
        }
        ChunkMetadata metadata = ChunkMetadata.builder()
                .provenance(blocks.get(0).provenance())
                .build();
        return List.of(ChunkAssembler.assemble(0, ChunkDraft.of(whole, metadata)));
    }
}
