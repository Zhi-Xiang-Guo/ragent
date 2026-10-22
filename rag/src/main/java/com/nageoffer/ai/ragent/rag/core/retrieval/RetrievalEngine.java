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

package com.nageoffer.ai.ragent.rag.core.retrieval;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScoreFilters;
import com.nageoffer.ai.ragent.rag.core.prompt.ContextFormatter;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.dto.RetrievalStageSnapshot;
import com.nageoffer.ai.ragent.rag.dto.RankedChunk;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionRetrievalResult;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import com.nageoffer.ai.ragent.rag.core.retrieval.context.ContextExpander;
import com.nageoffer.ai.ragent.rag.core.retrieval.context.ContextAssembly;
import com.nageoffer.ai.ragent.rag.core.retrieval.context.ContextUnit;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.CONTEXT_FORMAT_PATH;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.MULTI_CHANNEL_KEY;

/**
 * 检索引擎
 * 按子问题执行知识库检索，并组装回答所需的上下文
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalEngine {

    private final SearchChannelProperties searchProperties;
    private final ContextFormatter contextFormatter;
    private final PromptTemplateLoader templateLoader;
    private final MultiChannelRetrievalEngine multiChannelRetrievalEngine;
    private final Executor ragContextExecutor;
    private final List<ContextExpander> contextExpanders;

    @RagTraceNode(name = "retrieval-engine", type = "RETRIEVE")
    public RetrievalContext retrieve(List<SubQuestionIntent> subIntents) {
        return retrieve(subIntents, false);
    }

    public RetrievalContext retrieve(List<SubQuestionIntent> subIntents, boolean captureStages) {
        if (CollUtil.isEmpty(subIntents)) {
            return RetrievalContext.builder()
                    .intentChunks(Map.of())
                    .build();
        }

        // 所有子问题共用同一份检索预算
        int contextTopK = searchProperties.getDefaultTopK();
        RetrievalBudget budget = new RetrievalBudget(
                searchProperties.resolveRecallBudget(contextTopK),
                searchProperties.getFusion().getRerankCandidateLimit(),
                contextTopK
        );
        int wrapperBudget = 0;
        if (subIntents.size() > 1) {
            for (int i = 0; i < subIntents.size(); i++) {
                StringBuilder wrapper = new StringBuilder();
                appendSection(wrapper, i + 1, subIntents.get(i).subQuestion(), "");
                wrapperBudget += wrapper.codePointCount(0, wrapper.length()) + 1;
            }
        }
        final int perQuestionChars = Math.max(0, (8192 - wrapperBudget) / subIntents.size());
        List<CompletableFuture<SubQuestionContext>> tasks = subIntents.stream()
                .map(intent -> CompletableFuture.supplyAsync(
                        () -> {
                            List<RetrievalStageSnapshot> stages = new ArrayList<>();
                            try {
                                return buildSubQuestionContext(intent, budget, captureStages ? stages : null, perQuestionChars);
                            } catch (Exception e) {
                                log.error("子问题上下文构建失败，降级为空上下文，question：{}", intent.subQuestion(), e);
                                return new SubQuestionContext(
                                        intent.subQuestion(), "", Map.of(),
                                        KnowledgeRetrievalResult.empty().eligibleIntentIds(
                                                NodeScoreFilters.kb(intent.nodeScores())),
                                        new SubQuestionRetrievalResult(intent.subQuestion(), "ERROR", List.of(), stages), List.of());
                            }
                        },
                        ragContextExecutor
                ))
                .toList();
        List<SubQuestionContext> contexts = tasks.stream()
                .map(CompletableFuture::join)
                .toList();

        Map<String, List<RetrievedChunk>> mergedIntentChunks = new LinkedHashMap<>();
        Set<String> eligibleIntentIds = new LinkedHashSet<>();
        for (SubQuestionContext context : contexts) {
            eligibleIntentIds.addAll(context.eligibleIntentIds());
            if (CollUtil.isNotEmpty(context.intentChunks())) {
                context.intentChunks().forEach((intentId, chunks) -> {
                    if (CollUtil.isNotEmpty(chunks)) {
                        mergedIntentChunks
                                .computeIfAbsent(intentId, ignored -> new ArrayList<>())
                                .addAll(chunks);
                    }
                });
            }
        }

        String kbContext;
        if (contexts.size() == 1) {
            kbContext = StrUtil.emptyIfNull(contexts.get(0).kbContext()).trim();
        } else {
            StringBuilder kbBuilder = new StringBuilder();
            int globalIndex = 0;
            for (SubQuestionContext context : contexts) {
                if (StrUtil.isNotBlank(context.kbContext())) {
                    appendSection(kbBuilder, ++globalIndex, context.question(), context.kbContext());
                }
            }
            kbContext = kbBuilder.toString().trim();
        }

        return RetrievalContext.builder()
                .kbContext(kbContext)
                .intentChunks(mergedIntentChunks)
                .eligibleIntentIds(Set.copyOf(eligibleIntentIds))
                .subQuestionResults(contexts.stream().map(SubQuestionContext::retrievalResult).toList())
                .contextUnits(contexts.stream().flatMap(c -> c.contextUnits().stream()).toList())
                .build();
    }

    private SubQuestionContext buildSubQuestionContext(SubQuestionIntent intent, RetrievalBudget budget,
                                                       List<RetrievalStageSnapshot> stages, int contextChars) {
        List<NodeScore> kbIntents = NodeScoreFilters.kb(intent.nodeScores());
        return retrieveAndRerank(intent, kbIntents, budget, stages, contextChars);
    }

    private void appendSection(StringBuilder builder, int index, String question, String context) {
        if (!builder.isEmpty()) {
            builder.append("\n");
        }
        builder.append(templateLoader.renderSection(CONTEXT_FORMAT_PATH, "sub-question-kb-wrapper", Map.of(
                "index", String.valueOf(index),
                "question", question,
                "context", context
        )));
    }

    private SubQuestionContext retrieveAndRerank(SubQuestionIntent intent, List<NodeScore> kbIntents, RetrievalBudget budget,
                                                List<RetrievalStageSnapshot> stages, int contextChars) {
        KnowledgeRetrievalResult retrievalResult = stages == null
                ? multiChannelRetrievalEngine.retrieveKnowledgeChannels(intent, budget)
                : multiChannelRetrievalEngine.retrieveKnowledgeChannels(intent, budget, stages::add);
        List<RetrievedChunk> chunks = retrievalResult.chunks();
        Set<String> eligibleIntentIds = retrievalResult.eligibleIntentIds(kbIntents);
        SubQuestionRetrievalResult rankedResult = new SubQuestionRetrievalResult(
                intent.subQuestion(), "SUCCESS", RankedChunk.snapshot(chunks), stages == null ? List.of() : stages);
        if (CollUtil.isEmpty(chunks)) {
            return new SubQuestionContext(intent.subQuestion(), "", Map.of(), eligibleIntentIds, rankedResult, List.of());
        }

        Map<String, List<RetrievedChunk>> intentChunks = retrievalResult.groupByIntent(MULTI_CHANNEL_KEY);
        var assembly = ContextAssembly.assemble(chunks, contextExpanders,
                expanded -> contextFormatter.formatKbContext(kbIntents, eligibleIntentIds, expanded, budget.contextTopK()),
                contextChars);
        Set<String> usedIds = assembly.units().stream().flatMap(u -> u.hitChunkIds().stream())
                .collect(java.util.stream.Collectors.toSet());
        if (!contextExpanders.isEmpty()) {
            intentChunks.replaceAll((key, values) -> values.stream()
                    .filter(c -> c.getId() == null || usedIds.contains(c.getId())).toList());
        }
        return new SubQuestionContext(intent.subQuestion(), assembly.text(), intentChunks, eligibleIntentIds,
                rankedResult, assembly.units());
    }

    private record SubQuestionContext(String question,
                                      String kbContext,
                                      Map<String, List<RetrievedChunk>> intentChunks,
                                      Set<String> eligibleIntentIds,
                                      SubQuestionRetrievalResult retrievalResult,
                                      List<ContextUnit> contextUnits) {
    }
}
