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

package com.nageoffer.ai.ragent.rag.service.pipeline;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.convention.SourceRef;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.infra.chat.StreamCancellationHandle;
import com.nageoffer.ai.ragent.rag.core.guidance.GuidanceDecision;
import com.nageoffer.ai.ragent.rag.core.guidance.IntentGuidanceService;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptSlot;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptContext;
import com.nageoffer.ai.ragent.rag.core.prompt.RAGPromptService;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.core.source.CitationContextEnricher;
import com.nageoffer.ai.ragent.rag.core.source.GroundingChunksAssembler;
import com.nageoffer.ai.ragent.rag.core.source.SourcesAssembler;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.framework.web.StreamTaskManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 流式对话流水线
 * <p>
 * 承载从 RAGChatServiceImpl 提取的业务编排逻辑：
 * 记忆加载 -> 改写拆分 -> 意图解析 -> 歧义引导 -> 系统响应 / 检索 -> Prompt 组装 -> 流式输出
 * <p>
 * 流水线模式：通过私有方法 + boolean 返回值（handleXxx 返回 true 表示已处理并短路）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamChatPipeline {

    private final ConversationMemoryService memoryService;
    private final QueryRewriteService queryRewriteService;
    private final IntentResolver intentResolver;
    private final IntentGuidanceService guidanceService;
    private final RetrievalEngine retrievalEngine;
    private final LLMService llmService;
    private final RAGPromptService promptBuilder;
    private final AgentPromptResolver agentPromptResolver;
    private final StreamTaskManager taskManager;
    private final SourcesAssembler sourcesAssembler;
    private final GroundingChunksAssembler groundingChunksAssembler;
    private final CitationContextEnricher citationContextEnricher;

    /**
     * 按固定顺序执行一次流式 RAG 问答。
     *
     * <p>歧义引导、纯系统对话和无检索结果都会提前结束流水线；只有需要知识支撑且检索成功的
     * 请求，才会进入最终的 RAG 回答生成阶段。
     *
     * @param ctx 贯穿整个问答流程的可变上下文
     */
    public void execute(StreamChatContext ctx) {
        loadMemory(ctx);
        rewriteQuery(ctx);
        resolveIntents(ctx);

        if (handleGuidance(ctx)) {
            return;
        }
        if (handleSystemOnly(ctx)) {
            return;
        }

        RetrievalContext retrievalCtx = retrieve(ctx);
        if (handleEmptyRetrieval(ctx, retrievalCtx)) {
            return;
        }

        streamRagResponse(ctx, retrievalCtx);
    }

    // ==================== 流水线阶段 ====================

    /**
     * 加载当前会话的历史消息，并先持久化本轮用户问题。
     *
     * <p>问题消息 ID 会通过回调发送给前端，后续生成的回答可据此建立回复关系。
     *
     * @param ctx 流式问答上下文
     */
    private void loadMemory(StreamChatContext ctx) {
        List<ChatMessage> history = memoryService.load(ctx.getConversationId(), ctx.getUserId());
        String questionMessageId = memoryService.append(
                ctx.getConversationId(), ctx.getUserId(), ChatMessage.user(ctx.getQuestion()));
        ctx.getCallback().onReplyToMessageId(questionMessageId);
        ctx.setHistory(history);
    }

    /**
     * 结合历史消息消解指代、补齐上下文，并在需要时把复合问题拆成多个子问题。
     *
     * @param ctx 流式问答上下文；执行后写入问题改写结果
     */
    private void rewriteQuery(StreamChatContext ctx) {
        RewriteResult rewriteResult = queryRewriteService.rewriteWithSplit(ctx.getQuestion(), ctx.getHistory());
        ctx.setRewriteResult(rewriteResult);
    }

    /**
     * 为改写后的问题和各个子问题匹配意图树节点。
     *
     * <p>意图结果决定后续走系统对话、知识库检索还是 MCP 工具检索。
     *
     * @param ctx 流式问答上下文；执行后写入子问题意图列表
     */
    private void resolveIntents(StreamChatContext ctx) {
        List<SubQuestionIntent> subIntents = intentResolver.resolve(ctx.getRewriteResult());
        ctx.setSubIntents(subIntents);
    }

    /**
     * 检查当前意图是否存在歧义，并在需要时直接向用户返回澄清问题。
     *
     * @param ctx 流式问答上下文
     * @return {@code true} 表示已输出引导语并结束本次流水线，{@code false} 表示可以继续执行
     */
    private boolean handleGuidance(StreamChatContext ctx) {
        GuidanceDecision decision = guidanceService.detectAmbiguity(
                ctx.getRewriteResult().rewrittenQuestion(),
                ctx.getSubIntents()
        );
        if (!decision.isPrompt()) {
            return false;
        }
        StreamCallback callback = ctx.getCallback();
        callback.onContent(decision.getPrompt());
        callback.onComplete();
        return true;
    }

    /**
     * 处理无需访问知识库或工具的普通对话。
     *
     * <p>所有子问题都属于系统对话时，直接组装系统提示词和会话历史调用模型，同时把模型取消句柄
     * 绑定到任务 ID，供停止接口使用。
     *
     * @param ctx 流式问答上下文
     * @return {@code true} 表示已启动普通对话生成，{@code false} 表示仍需进入检索流程
     */
    private boolean handleSystemOnly(StreamChatContext ctx) {
        List<SubQuestionIntent> subIntents = ctx.getSubIntents();
        boolean allSystemOnly = subIntents.stream()
                .allMatch(si -> intentResolver.isSystemOnly(si.nodeScores()));
        if (!allSystemOnly) {
            return false;
        }
        String customPrompt = subIntents.stream()
                .flatMap(si -> si.nodeScores().stream())
                .map(ns -> ns.getNode().getPromptTemplate())
                .filter(StrUtil::isNotBlank)
                .findFirst()
                .orElse(null);
        StreamCancellationHandle handle = streamSystemResponse(
                ctx.getRewriteResult().rewrittenQuestion(),
                ctx.getHistory(),
                customPrompt,
                ctx.getCallback()
        );
        taskManager.bindHandle(ctx.getTaskId(), handle == null ? null : handle::cancel);
        return true;
    }

    /**
     * 根据各子问题的意图执行多通道检索。
     *
     * @param ctx 流式问答上下文
     * @return 汇总知识库内容、MCP 结果和命中意图的检索上下文
     */
    private RetrievalContext retrieve(StreamChatContext ctx) {
        return retrievalEngine.retrieve(ctx.getSubIntents());
    }

    /**
     * 在所有检索通道均无结果时返回固定提示，避免模型脱离资料自由生成答案。
     *
     * @param ctx 流式问答上下文
     * @param retrievalCtx 检索结果上下文
     * @return {@code true} 表示已返回空结果提示并结束流水线，{@code false} 表示存在可用资料
     */
    private boolean handleEmptyRetrieval(StreamChatContext ctx, RetrievalContext retrievalCtx) {
        if (!retrievalCtx.isEmpty()) {
            return false;
        }
        StreamCallback callback = ctx.getCallback();
        callback.onContent("未检索到与问题相关的文档内容。");
        callback.onComplete();
        return true;
    }

    /**
     * 整理检索结果的引用来源和 grounding 片段，并启动最终的 RAG 流式生成。
     *
     * <p>来源列表用于前端展示和答案引用；grounding 片段随消息落库，供后续生成推荐追问使用。
     * 模型返回的取消句柄会绑定到任务 ID。
     *
     * @param ctx 流式问答上下文
     * @param retrievalCtx 包含知识库及工具结果的检索上下文
     */
    private void streamRagResponse(StreamChatContext ctx, RetrievalContext retrievalCtx) {
        // 检索完成后建立唯一来源编号：同一列表用于完成事件、来源面板与消息落库，开启引用时还作为行内角标编号
        List<SourceRef> sources = sourcesAssembler.assemble(retrievalCtx.getIntentChunks());
        ctx.getCallback().onSources(sources);
        // 开关关闭时这一步只负责清掉上下文里的内部 docId，不注入编号
        retrievalCtx.setKbContext(citationContextEnricher.enrich(retrievalCtx.getKbContext(), sources));

        // 装配 grounding 片段随消息落库 供答案后推荐追问生成 grounding（不参与 prompt）
        ctx.getCallback().onGroundingChunks(groundingChunksAssembler.assemble(retrievalCtx.getIntentChunks()));

        StreamCancellationHandle handle = streamLLMResponse(
                ctx.getRewriteResult(),
                retrievalCtx,
                intentResolver.mergeKbIntents(ctx.getSubIntents()),
                ctx.getHistory(),
                ctx.isDeepThinking(),
                ctx.getCallback()
        );
        taskManager.bindHandle(ctx.getTaskId(), handle == null ? null : handle::cancel);
    }

    // ==================== LLM 响应 ====================

    /**
     * 组装普通聊天消息并调用模型进行流式生成。
     *
     * @param question 改写后的用户问题
     * @param history 会话历史消息
     * @param customPrompt 意图节点配置的提示词；为空时使用默认系统聊天提示词
     * @param callback 接收模型增量输出和完成事件的回调
     * @return 模型请求取消句柄
     */
    private StreamCancellationHandle streamSystemResponse(String question, List<ChatMessage> history,
                                                          String customPrompt, StreamCallback callback) {
        String systemPrompt = StrUtil.isNotBlank(customPrompt)
                ? customPrompt
                : agentPromptResolver.resolve(AgentPromptSlot.SYSTEM_CHAT);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        if (CollUtil.isNotEmpty(history)) {
            messages.addAll(history);
        }
        messages.add(ChatMessage.user(question));

        ChatRequest req = ChatRequest.builder()
                .messages(messages)
                .temperature(0.7D)
                .thinking(false)
                .build();
        return llmService.streamChat(req, callback);
    }

    /**
     * 把改写结果、检索资料、命中意图和会话历史组装为结构化 Prompt，并流式调用模型。
     *
     * <p>知识库回答采用稳定的低温度；包含 MCP 结果时适当提高温度与采样范围，以便模型组织工具结果。
     *
     * @param rewriteResult 问题改写及拆分结果
     * @param ctx 检索结果上下文
     * @param intentGroup 合并后的知识库和 MCP 意图
     * @param history 会话历史消息
     * @param deepThinking 是否启用模型的深度思考能力
     * @param callback 接收模型增量输出和完成事件的回调
     * @return 模型请求取消句柄
     */
    private StreamCancellationHandle streamLLMResponse(RewriteResult rewriteResult, RetrievalContext ctx,
                                                       List<NodeScore> kbIntents,
                                                       List<ChatMessage> history,
                                                       boolean deepThinking, StreamCallback callback) {
        PromptContext promptContext = PromptContext.builder()
                .kbContext(ctx.getKbContext())
                .kbIntents(kbIntents)
                .eligibleIntentIds(ctx.getEligibleIntentIds())
                .build();

        List<ChatMessage> messages = promptBuilder.buildStructuredMessages(
                promptContext,
                history,
                rewriteResult.rewrittenQuestion(),
                rewriteResult.subQuestions()  // 传入子问题列表
        );
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(messages)
                .thinking(deepThinking)
                .temperature(0D)
                .topP(1D)
                .build();

        return llmService.streamChat(chatRequest, callback);
    }
}
