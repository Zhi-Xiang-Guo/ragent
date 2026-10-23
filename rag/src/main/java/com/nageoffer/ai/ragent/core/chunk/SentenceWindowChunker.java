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

import com.nageoffer.ai.ragent.core.chunk.blockaware.HeadingHandler;
import com.nageoffer.ai.ragent.core.chunk.model.Chunk;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkAssembler;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkBudget;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkDraft;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkMetadata;
import com.nageoffer.ai.ragent.core.chunk.text.TextSplitter;
import com.nageoffer.ai.ragent.core.ingest.IndexingOptions;
import com.nageoffer.ai.ragent.core.parser.BlockTextRenderer;
import com.nageoffer.ai.ragent.core.parser.model.Block;
import com.nageoffer.ai.ragent.core.parser.model.HeadingBlock;
import com.nageoffer.ai.ragent.core.parser.model.ListBlock;
import com.nageoffer.ai.ragent.core.parser.model.ParagraphBlock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Sentence units keep document order; list items and structured blocks are atomic before length fallback. */
public final class SentenceWindowChunker {
    private SentenceWindowChunker() { }
    public static List<Chunk> chunk(List<Block> blocks, IndexingOptions options, ChunkingService structural) {
        List<Chunk> output = new ArrayList<>();
        var headingHandler = new HeadingHandler();
        var outline = HeadingHandler.Outline.EMPTY;
        String section = ChunkAssembler.nextChunkId();
        String heading = "";
        if (blocks == null) return List.of();
        for (Block block : blocks) {
            if (block instanceof HeadingBlock h) {
                outline = headingHandler.update(outline, h);
                section = ChunkAssembler.nextChunkId();
                heading = BlockTextRenderer.render(List.of(h));
                continue;
            }
            List<String> units = new ArrayList<>();
            if (block instanceof ParagraphBlock p) {
                if (p.text() != null) units.addAll(Arrays.asList(p.text().split("(?<=[。！？!?])|(?<=\\.)\\s+|\\n+")));
            } else if (block instanceof ListBlock list) {
                if (list.items() != null) for (int i = 0; i < list.items().size(); i++)
                    units.add((list.ordered() ? (i + 1) + ". " : "- ") + list.items().get(i));
            } else {
                // Reuse table header and fenced-code aware splitting; do not split these on punctuation.
                for (Chunk source : structural.chunk(List.of(block), new ChunkBudget(options.maxSentenceChars(), 0, 1, 1))) {
                    String content = heading.isEmpty() ? source.content() : heading + "\n\n" + source.content();
                    heading = "";
                    var metadata = new ChunkMetadata(outline.path(), source.metadata().assets(), block.provenance(),
                            Map.of("chunk_type", "SENTENCE", "section_id", section));
                    output.add(ChunkAssembler.assemble(output.size(), ChunkDraft.of(content, source.embeddingText(), metadata)));
                }
                continue;
            }
            for (String unit : units) {
                for (String piece : TextSplitter.split(unit, options.maxSentenceChars(), 0)) {
                    if (piece.isBlank()) continue;
                    String content = heading.isEmpty() ? piece : heading + "\n\n" + piece;
                    heading = "";
                    var metadata = new ChunkMetadata(outline.path(), List.of(), block.provenance(),
                            Map.of("chunk_type", "SENTENCE", "section_id", section));
                    output.add(ChunkAssembler.assemble(output.size(), ChunkDraft.of(content, metadata)));
                }
            }
        }
        return output;
    }
}
