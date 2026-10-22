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
import java.util.List;

/** Expanded context, separate from the immutable raw retrieval ranking. */
public record ContextUnit(RetrievedChunk chunk, List<String> hitChunkIds, String status,
                          String sectionId, Integer startIndex, Integer endIndex) {
    public ContextUnit {
        chunk = chunk.toBuilder().build();
        hitChunkIds = List.copyOf(hitChunkIds);
    }
    public static ContextUnit original(RetrievedChunk hit) {
        return new ContextUnit(hit, hit.getId() == null ? List.of() : List.of(hit.getId()),
                "ORIGINAL", null, hit.getChunkIndex(), hit.getChunkIndex());
    }
    public ContextUnit withText(String text) {
        return new ContextUnit(chunk.toBuilder().text(text).build(), hitChunkIds,
                status + ":TRUNCATED", sectionId, startIndex, endIndex);
    }
}
