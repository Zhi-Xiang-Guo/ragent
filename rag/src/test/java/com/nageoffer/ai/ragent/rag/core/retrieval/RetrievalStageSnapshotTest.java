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

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.*;
import com.nageoffer.ai.ragent.rag.core.retrieval.postprocessor.*;
import com.nageoffer.ai.ragent.rag.dto.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RetrievalStageSnapshotTest {
    @Test
    void capturesFusionBeforeTruncationWithoutMutatingEarlierScores() {
        var properties = new SearchChannelProperties();
        var vector = channel(SearchChannelType.VECTOR);
        var keyword = channel(SearchChannelType.KEYWORD);
        when(vector.search(any())).thenAnswer(i -> result(SearchChannelType.VECTOR, chunk("a", .9f), chunk("b", .8f)));
        when(keyword.search(any())).thenAnswer(i -> result(SearchChannelType.KEYWORD, chunk("b", 20f), chunk("c", 10f)));
        var engine = engine(List.of(vector, keyword), List.of(new DeduplicationPostProcessor(), new FusionPostProcessor(properties)), Runnable::run, properties);
        var trace = new ArrayList<RetrievalStageSnapshot>();
        var input = new SubQuestionIntent("q", List.of());
        var budget = new RetrievalBudget(3, 1, 1);
        var traced = engine.retrieveKnowledgeChannels(input, budget, trace::add);
        var plain = engine.retrieveKnowledgeChannels(input, budget);
        assertEquals(plain.chunks(), traced.chunks());
        assertEquals(1, traced.chunks().size());
        assertEquals("b", traced.chunks().get(0).getId());
        assertEquals(.9f, trace.get(0).rankedChunks().get(0).score());
        assertEquals(3, trace.stream().filter(s -> s.stage().equals("fusion:ranked")).findFirst().orElseThrow().rankedChunks().size());
        assertEquals(1, trace.stream().filter(s -> s.stage().equals("post:Fusion")).findFirst().orElseThrow().rankedChunks().size());
        verify(vector, times(2)).search(any());
    }

    @Test
    void distinguishesEngineFailureAndUnknownEmptyResults() {
        var vector = channel(SearchChannelType.VECTOR);
        var keyword = channel(SearchChannelType.KEYWORD);
        when(vector.search(any())).thenThrow(new IllegalStateException("down"));
        when(keyword.search(any())).thenReturn(result(SearchChannelType.KEYWORD));
        var trace = new ArrayList<RetrievalStageSnapshot>();
        engine(List.of(vector, keyword), List.of(), Runnable::run, new SearchChannelProperties())
                .retrieveKnowledgeChannels(new SubQuestionIntent("q", List.of()), RetrievalBudget.uniform(2), trace::add);
        assertEquals(List.of("ERROR", "EMPTY_OR_DEGRADED"), trace.stream().map(RetrievalStageSnapshot::status).toList());
    }

    @Test
    void timeoutDoesNotAllowLateWritesToSnapshotCollector() throws Exception {
        var vector = channel(SearchChannelType.VECTOR);
        var release = new CountDownLatch(1);
        when(vector.search(any())).thenAnswer(i -> {
            release.await(2, TimeUnit.SECONDS);
            return result(SearchChannelType.VECTOR, chunk("late", 1f));
        });
        var properties = new SearchChannelProperties();
        properties.getChannels().setTimeoutMs(30);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var trace = new ArrayList<RetrievalStageSnapshot>();
            engine(List.of(vector), List.of(), pool, properties)
                    .retrieveKnowledgeChannels(new SubQuestionIntent("q", List.of()), RetrievalBudget.uniform(2), trace::add);
            assertEquals("TIMEOUT", trace.get(0).status());
            release.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals(1, trace.size());
            assertTrue(trace.get(0).rankedChunks().isEmpty());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentRequestsKeepSeparateCollectors() throws Exception {
        var vector = channel(SearchChannelType.VECTOR);
        when(vector.search(any())).thenAnswer(i -> result(SearchChannelType.VECTOR,
                chunk(((SearchContext) i.getArgument(0)).getMainQuestion(), 1f)));
        var engine = engine(List.of(vector), List.of(), Runnable::run, new SearchChannelProperties());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> capture(engine, "first"));
            var second = pool.submit(() -> capture(engine, "second"));
            assertEquals("first", first.get().get(0).rankedChunks().get(0).chunkId());
            assertEquals("second", second.get().get(0).rankedChunks().get(0).chunkId());
        } finally {
            pool.shutdownNow();
        }
    }

    private List<RetrievalStageSnapshot> capture(MultiChannelRetrievalEngine engine, String query) {
        var trace = new ArrayList<RetrievalStageSnapshot>();
        engine.retrieveKnowledgeChannels(new SubQuestionIntent(query, List.of()), RetrievalBudget.uniform(2), trace::add);
        return trace;
    }

    private MultiChannelRetrievalEngine engine(List<SearchChannel> channels, List<SearchResultPostProcessor> processors,
                                               Executor executor, SearchChannelProperties properties) {
        var resolver = mock(RetrievalScopeResolver.class);
        when(resolver.resolve(any())).thenReturn(RetrievalScope.global(0, List.of("kb")));
        return new MultiChannelRetrievalEngine(channels, processors, resolver, executor, properties);
    }

    private SearchChannel channel(SearchChannelType type) {
        var channel = mock(SearchChannel.class);
        when(channel.getName()).thenReturn(type.name());
        when(channel.getType()).thenReturn(type);
        when(channel.isEnabled(any())).thenReturn(true);
        when(channel.emptyResult(anyLong())).thenCallRealMethod();
        return channel;
    }

    private SearchChannelResult result(SearchChannelType type, RetrievedChunk... chunks) {
        return SearchChannelResult.builder().channelType(type).channelName(type.name()).chunks(List.of(chunks)).build();
    }

    private RetrievedChunk chunk(String id, float score) {
        return RetrievedChunk.builder().id(id).text(id).collectionName("kb").score(score).build();
    }
}
