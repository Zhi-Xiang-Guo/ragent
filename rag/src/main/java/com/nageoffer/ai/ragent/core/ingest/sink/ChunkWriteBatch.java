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

package com.nageoffer.ai.ragent.core.ingest.sink;
import com.nageoffer.ai.ragent.core.chunk.model.Chunk;
import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
/** Parents never carry vectors and can only be consumed by relational storage. */
public record ChunkWriteBatch(List<EmbeddedChunk> searchable, List<Chunk> parents) {
    public ChunkWriteBatch {
        searchable = List.copyOf(searchable);
        parents = List.copyOf(parents);
        Set<String> ids = new HashSet<>();
        for (Chunk parent : parents) {
            if (!"PARENT".equals(parent.metadata().extras().get("chunk_type")) || !ids.add(parent.chunkId()))
                throw new IllegalArgumentException("Invalid or duplicate parent");
        }
        Set<String> childIds = new HashSet<>();
        for (EmbeddedChunk child : searchable) {
            if (!childIds.add(child.chunk().chunkId()) || ids.contains(child.chunk().chunkId()))
                throw new IllegalArgumentException("Duplicate chunk identity in document batch");
            String type = (String) child.metadata().extras().getOrDefault("chunk_type", "STANDARD");
            if (type.equals("PARENT")) throw new IllegalArgumentException("Parent must not be embedded");
            if (type.equals("CHILD") && !ids.contains(child.metadata().extras().get("parent_id")))
                throw new IllegalArgumentException("Child references a parent outside this document batch");
        }
    }
}
