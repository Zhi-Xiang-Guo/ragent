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

package com.nageoffer.ai.ragent.rag.dto;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunkKey;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 不可变的检索排名快照；score 的量纲取决于当前处理阶段，缺少精排分保持 null。 */
public record RankedChunk(int rank, String collectionName, String chunkId, String docId,
                          String text, Float score, Float rerankScore) {

    /** 保留首次出现顺序，按 collection + chunk key 去重，不根据分数重新排序。 */
    public static List<RankedChunk> snapshot(List<RetrievedChunk> chunks) {
        List<RankedChunk> result = new ArrayList<>();
        Set<List<String>> seen = new HashSet<>();
        for (RetrievedChunk chunk : chunks) {
            String collection = chunk.getCollectionName() == null ? "" : chunk.getCollectionName();
            if (seen.add(List.of(collection, RetrievedChunkKey.of(chunk)))) {
                result.add(new RankedChunk(result.size() + 1, chunk.getCollectionName(), chunk.getId(),
                        chunk.getDocId(), chunk.getText(), chunk.getScore(), chunk.getRerankScore()));
            }
        }
        return List.copyOf(result);
    }
}
