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
    @Test void splitsChineseAndEnglishButKeepsDecimalsListsAndSectionBoundaries() {
        var chunks = service().chunkForIngestion(parse("# 配方\n\n加入1.5克盐。等待十分钟！Then stir. Let it cool.\n\n- 打开包装。检查封口。\n\n# 储存\n\n冷藏。不要冷冻。"),
                new IngestionSpec(3, null, ChunkBudget.defaults(), new IndexingOptions("SENTENCE_WINDOW", 2, 384)));
        assertEquals(7, chunks.size());
        assertTrue(chunks.get(0).content().contains("1.5克盐。"));
        assertEquals("Then stir. ".trim(), chunks.get(2).content().trim());
        assertTrue(chunks.get(4).content().contains("打开包装。检查封口。"));
        assertNotEquals(chunks.get(4).metadata().extras().get("section_id"), chunks.get(5).metadata().extras().get("section_id"));
        for (int i=0;i<chunks.size();i++) assertEquals(i, chunks.get(i).index());
    }
    @Test void emptyAndLongUnpunctuatedInputHaveDeterministicBounds() {
        var spec = new IngestionSpec(3, null, ChunkBudget.defaults(), new IndexingOptions("SENTENCE_WINDOW", 2, 384));
        assertTrue(service().chunkForIngestion(List.of(), spec).isEmpty());
        var units = service().chunkForIngestion(parse("长".repeat(1000)), spec);
        assertEquals(3, units.size());
        assertEquals("长".repeat(1000), units.stream().map(Chunk::content).collect(java.util.stream.Collectors.joining()));
    }
    @Test void structuralUnitsPreserveImageAssetsAndSparseHeadingHierarchy() {
        var spec = new IngestionSpec(3, null, ChunkBudget.defaults(), new IndexingOptions("SENTENCE_WINDOW", 2, 384));
        var asset = new com.nageoffer.ai.ragent.core.parser.model.AssetRef("https://example.test/picture.png", "image/png");
        var image = new com.nageoffer.ai.ragent.core.parser.model.ImageBlock(null, asset, "图", "图", "恢复状态截图");
        var units = service().chunkForIngestion(List.of(image), spec);
        assertEquals(List.of(asset), units.get(0).metadata().assets());
        assertTrue(units.get(0).content().contains(asset.publicUrl()));
        assertFalse(units.get(0).embeddingText().contains(asset.publicUrl()));
        var headings = service().chunkForIngestion(parse("# 配方\n\n### 步骤甲\n\n甲。\n\n### 步骤乙\n\n乙。"), spec);
        assertEquals(List.of("配方", "步骤乙"), headings.get(headings.size()-1).metadata().outlinePath());
    }
}
