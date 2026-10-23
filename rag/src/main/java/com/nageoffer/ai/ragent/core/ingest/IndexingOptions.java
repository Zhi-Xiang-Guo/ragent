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

/** Indexing parameters; persisted inside the existing document ingestion_spec JSONB. */
public record IndexingOptions(String mode, int parentMaxChars, int childMaxChars, int childOverlapChars) {
    public IndexingOptions {
        mode = mode == null ? "STANDARD" : mode.trim().toUpperCase(java.util.Locale.ROOT);
        if (!mode.equals("STANDARD") && !mode.equals("PARENT_CHILD"))
            throw new IllegalArgumentException("Unsupported indexingMode: " + mode);
        if (parentMaxChars < 1 || parentMaxChars > 8192 || childMaxChars < 1 || childMaxChars > parentMaxChars
                || childOverlapChars < 0 || childOverlapChars >= childMaxChars)
            throw new IllegalArgumentException("Invalid parent/child budgets");
    }
    public boolean advanced() { return !mode.equals("STANDARD"); }
    public static IndexingOptions defaults() { return new IndexingOptions("STANDARD", 2048, 384, 64); }
}
