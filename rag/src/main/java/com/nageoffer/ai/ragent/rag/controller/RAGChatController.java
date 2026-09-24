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

package com.nageoffer.ai.ragent.rag.controller;

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.validation.ChatQuestion;
import com.nageoffer.ai.ragent.framework.idempotent.IdempotentSubmit;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.service.RAGChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * RAG 对话控制器
 * 提供流式问答与任务取消接口
 */
@RestController
@RequiredArgsConstructor
public class RAGChatController {

    private final RAGChatService ragChatService;
    private final RAGDefaultProperties ragDefaultProperties;

    /**
     * 创建 SSE 长连接并发起一次流式问答。
     *
     * <p>控制器只负责接收和校验请求、创建 SSE 发射器；排队、检索、模型生成等流程交给
     * {@link RAGChatService}。同一用户已有问答正在执行时，幂等注解会拒绝重复提交。
     *
     * @param question 用户输入的问题
     * @param conversationId 会话 ID；为空时由服务层创建新会话
     * @param deepThinking 是否启用模型的深度思考能力
     * @return 用于持续向浏览器推送回答事件的 SSE 发射器
     */
    @IdempotentSubmit(
            key = "T(com.nageoffer.ai.ragent.framework.context.UserContext).getUserId()",
            message = "当前会话处理中，请稍后再发起新的对话"
    )
    @GetMapping(value = "/rag/v3/chat", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chat(@RequestParam @ChatQuestion String question,
                           @RequestParam(required = false) String conversationId,
                           @RequestParam(required = false, defaultValue = "false") Boolean deepThinking) {
        SseEmitter emitter = new SseEmitter(ragDefaultProperties.getSseTimeoutMs());
        ragChatService.streamChat(question, conversationId, deepThinking, emitter);
        return emitter;
    }

    /**
     * 停止当前用户发起的指定流式问答任务。
     *
     * @param taskId 开始问答时生成并通过 SSE 返回给前端的任务 ID
     * @return 统一的成功响应
     */
    @IdempotentSubmit
    @PostMapping(value = "/rag/v3/stop")
    public Result<Void> stop(@RequestParam String taskId) {
        ragChatService.stopTask(taskId);
        return Results.success();
    }
}
