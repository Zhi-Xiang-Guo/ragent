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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Bound the rendered context, including template overhead, without cutting markup. */
public final class ContextAssembly {
    private ContextAssembly() { }
    public record Result(String text, List<ContextUnit> units) { }

    public static Result assemble(List<RetrievedChunk> hits, List<ContextExpander> expanders,
                                  Function<List<RetrievedChunk>, String> render, int budget) {
        return assemble(hits, expanders, render, budget, 0);
    }

    public static Result assemble(List<RetrievedChunk> hits, List<ContextExpander> expanders,
                                  Function<List<RetrievedChunk>, String> render, int budget, int maxUnits) {
        if (expanders.size() > 1) throw new IllegalStateException("Only one context expander is supported");
        List<ContextUnit> units = new ArrayList<>(expanders.isEmpty()
                ? hits.stream().map(ContextUnit::original).toList() : expanders.get(0).expand(hits));
        if (maxUnits > 0 && units.size() > maxUnits) units = new ArrayList<>(units.subList(0, maxUnits));
        while (!units.isEmpty() && budget > 0) {
            String text = render.apply(units.stream().map(ContextUnit::chunk).toList());
            if (text == null) text = "";
            int excess = text.codePointCount(0, text.length()) - budget;
            if (excess <= 0) return new Result(text, List.copyOf(units));
            int last = units.size() - 1;
            ContextUnit unit = units.get(last);
            String body = unit.chunk().getText();
            int size = body == null ? 0 : body.codePointCount(0, body.length());
            int keep = size - excess - 1;
            if (keep <= 0) { units.remove(last); continue; }
            // Center the retained slice on the best original hit, not an unrelated parent prefix.
            String evidence = hits.stream().filter(h -> unit.hitChunkIds().contains(h.getId()))
                    .map(RetrievedChunk::getText).filter(t -> t != null && !t.isBlank()).findFirst().orElse("");
            int found = evidence.isEmpty() ? 0 : body.indexOf(evidence);
            int anchor = found < 0 ? 0 : body.codePointCount(0, found);
            int evidenceSize = evidence.codePointCount(0, evidence.length());
            int start = Math.max(0, Math.min(size - keep, anchor - Math.max(0, keep - evidenceSize) / 2));
            units.set(last, unit.withText(body.substring(body.offsetByCodePoints(0, start),
                    body.offsetByCodePoints(0, start + keep))));
        }
        return new Result("", List.of());
    }
}
