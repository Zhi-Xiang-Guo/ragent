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
import com.nageoffer.ai.ragent.core.chunk.blockaware.*;
import com.nageoffer.ai.ragent.core.chunk.blockaware.CodeChunker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.HeadingChunker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.HtmlTableChunker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.ImageChunker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.ListChunker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.ParagraphChunker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.TableChunker;
import com.nageoffer.ai.ragent.core.chunk.model.*;
import com.nageoffer.ai.ragent.core.ingest.*;
import com.nageoffer.ai.ragent.core.parser.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AdvancedIndexingTest {
    private ChunkingService service() {
        return new ChunkingService(new BlockAwareChunkerDispatcher(new HeadingHandler(), new ChunkPacker(),
                List.of(new HeadingChunker(), new ParagraphChunker(), new TableChunker(), new HtmlTableChunker(),
                        new ImageChunker(), new CodeChunker(), new ListChunker())));
    }
    private java.util.List<com.nageoffer.ai.ragent.core.parser.model.Block> parse(String markdown) {
        return new MarkdownDocumentParser().parseStructured(markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "text/markdown", Map.of()).blocks();
    }
    @Test void childrenReferenceOnlyTheirSectionAndParentsAreNotSearchable() {
        var options = new IndexingOptions("PARENT_CHILD", 2048, 120, 20);
        var batch = service().chunkForIngestion(parse("# 甲流程\n\n" + "甲流程步骤，完成检查后执行。".repeat(70)
                + "\n\n# 乙流程\n\n" + "乙流程只允许人工复核。".repeat(70)),
                new IngestionSpec(3, null, ChunkBudget.defaults(), options));
        assertTrue(batch.parents().size() >= 2);
        assertTrue(batch.searchable().size() > batch.parents().size());
        var parents = batch.parents().stream().collect(java.util.stream.Collectors.toMap(Chunk::chunkId, c -> c));
        for (var child : batch.searchable()) {
            var parent = parents.get(child.metadata().extras().get("parent_id"));
            assertNotNull(parent);
            assertTrue(parent.content().contains(child.content()));
            assertEquals("CHILD", child.metadata().extras().get("chunk_type"));
            assertFalse(parent.content().contains("甲流程") && parent.content().contains("乙流程"));
        }
    }
    @Test void writeBatchRejectsDanglingParentAndEmbeddedParent() {
        var c = ChunkAssembler.assemble(0, ChunkDraft.of("正文", ChunkMetadata.empty().withExtras(
                Map.of("chunk_type", "CHILD", "parent_id", "absent"))));
        assertThrows(IllegalArgumentException.class, () -> new com.nageoffer.ai.ragent.core.ingest.sink.ChunkWriteBatch(
                List.of(new EmbeddedChunk(c, new float[]{1})), List.of()));
        var p = c.withMetadata(ChunkMetadata.empty().withExtras(Map.of("chunk_type", "PARENT")));
        assertThrows(IllegalArgumentException.class, () -> new com.nageoffer.ai.ragent.core.ingest.sink.ChunkWriteBatch(
                List.of(new EmbeddedChunk(p, new float[]{1})), List.of()));
    }
}
