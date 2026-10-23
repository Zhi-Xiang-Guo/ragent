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

package com.nageoffer.ai.ragent.core.ingest;
import com.nageoffer.ai.ragent.core.chunk.ChunkingService;
import com.nageoffer.ai.ragent.core.chunk.model.*;
import com.nageoffer.ai.ragent.core.ingest.embed.ChunkEmbeddingService;
import com.nageoffer.ai.ragent.core.ingest.sink.ChunkIndexWriter;
import com.nageoffer.ai.ragent.core.parser.DocumentParser;
import com.nageoffer.ai.ragent.core.parser.model.ParsedDocument;
import com.nageoffer.ai.ragent.core.parser.registry.ParserRegistry;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class IngestionFailureBoundaryTest {
    @Test void failedEmbeddingNeverReplacesActiveDocumentBatch() {
        var registry = mock(ParserRegistry.class); var parser = mock(DocumentParser.class);
        var chunker = mock(ChunkingService.class); var embed = mock(ChunkEmbeddingService.class);
        var writer = mock(ChunkIndexWriter.class);
        when(registry.require(any(),any())).thenReturn(parser);
        when(parser.parseStructured(any(),any(),any())).thenReturn(ParsedDocument.of(List.of()));
        var unit = ChunkAssembler.assemble(0, ChunkDraft.of("new evidence", ChunkMetadata.empty()));
        when(chunker.chunkForIngestion(any(),any())).thenReturn(List.of(unit));
        when(embed.embed(any(),any())).thenThrow(new IllegalStateException("model unavailable"));
        var kernel = new DefaultIngestionKernel(registry,chunker,embed,writer);
        assertThrows(IllegalStateException.class, () -> kernel.run(new DocumentRef("d","kb","test.md"),
                "# test\nnew evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                IngestionSpec.defaults(),new VectorTarget("col","model",3)));
        verifyNoInteractions(writer);
    }
}
