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

package com.nageoffer.ai.ragent.ingestion.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.rag.controller.request.IntentNodeUpdateRequest;
import com.nageoffer.ai.ragent.rag.core.intent.IntentTreeCacheManager;
import com.nageoffer.ai.ragent.rag.dao.entity.IntentNodeDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.IntentNodeMapper;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 仅在显式指定独立的 intent_parent_test 数据库时运行，不依赖应用数据库。
 */
@EnabledIfEnvironmentVariable(named = "RAGENT_PARENT_TEST_JDBC_URL", matches = ".+")
class IntentParentPostgresTest {

    private final IntentTreeCacheManager cache = mock(IntentTreeCacheManager.class);
    private JdbcTemplate jdbc;
    private IntentNodeMapper mapper;
    private IntentNodeMapper realMapper;
    private IntentTreeServiceImpl service;

    @BeforeEach
    void setUp() {
        String url = System.getenv("RAGENT_PARENT_TEST_JDBC_URL");
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:\\d+/intent_parent_test"));
        var dataSource = new DriverManagerDataSource(url, "postgres", "intent-parent-test");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS t_intent_node");
        jdbc.execute("""
                CREATE TABLE t_intent_node (
                    id TEXT PRIMARY KEY, kb_id TEXT, intent_code TEXT UNIQUE, name TEXT,
                    level INTEGER, parent_code TEXT, description TEXT, examples TEXT,
                    collection_name TEXT, collection_names JSONB, mcp_tool_id TEXT,
                    require_confirm INTEGER, top_k INTEGER, kind INTEGER, sort_order INTEGER,
                    prompt_snippet TEXT, prompt_template TEXT, enabled INTEGER,
                    create_by TEXT, update_by TEXT, create_time TIMESTAMP, update_time TIMESTAMP,
                    deleted INTEGER DEFAULT 0
                )
                """);
        jdbc.update("""
                INSERT INTO t_intent_node (id, intent_code, name, kind, level, enabled)
                VALUES ('1', 'a', 'A', 1, 0, 0), ('2', 'b', 'B', 1, 0, 0)
                """);

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("test", new SpringManagedTransactionFactory(), dataSource));
        var factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        configuration.addMapper(IntentNodeMapper.class);
        realMapper = new SqlSessionTemplate(factory).getMapper(IntentNodeMapper.class);
        mapper = mock(IntentNodeMapper.class, delegatesTo(realMapper));
        var target = new IntentTreeServiceImpl(mapper, mock(KnowledgeBaseMapper.class), cache,
                mock(BizChangeLogContext.class));
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
        service = (IntentTreeServiceImpl) proxy.getProxy();
    }

    @Test
    void concurrentOppositeMovesCommitOnlyOneRelationship() throws Exception {
        // 两个请求都在读取关系之前就位，保证覆盖实际竞争，而非顺序调用。
        CyclicBarrier readyToValidate = new CyclicBarrier(2);
        doAnswer(invocation -> {
            readyToValidate.await(5, TimeUnit.SECONDS);
            return realMapper.selectList(invocation.getArgument(0));
        }).when(mapper).selectList(any(Wrapper.class));

        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> move("1", "b"));
            var second = executor.submit(() -> move("2", "a"));
            int committed = (first.get(10, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(10, TimeUnit.SECONDS) ? 1 : 0);

            assertEquals(1, committed);
            assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM t_intent_node WHERE parent_code IS NULL", Integer.class));
            verify(cache).clearIntentTreeCache();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentRenameCannotRestoreAnOldParentAndRecreateACycle() throws Exception {
        jdbc.update("UPDATE t_intent_node SET parent_code = 'b' WHERE id = '1'");
        jdbc.update("INSERT INTO t_intent_node (id, intent_code, name, kind, level, enabled) "
                + "VALUES ('3', 'c', 'C', 1, 0, 0)");
        CountDownLatch renameReady = new CountDownLatch(1);
        CountDownLatch allowRename = new CountDownLatch(1);
        doAnswer(invocation -> {
            IntentNodeDO update = invocation.getArgument(0);
            if ("renamed".equals(update.getName())) {
                renameReady.countDown();
                assertTrue(allowRename.await(5, TimeUnit.SECONDS));
            }
            return realMapper.update(update, invocation.getArgument(1));
        }).when(mapper).update(any(IntentNodeDO.class), any(Wrapper.class));

        var executor = Executors.newSingleThreadExecutor();
        try {
            var rename = executor.submit(() -> service.updateNode("1",
                    IntentNodeUpdateRequest.builder().name("renamed").build()));
            assertTrue(renameReady.await(5, TimeUnit.SECONDS));

            service.updateNode("1", IntentNodeUpdateRequest.builder().parentCode("c").build());
            service.updateNode("2", IntentNodeUpdateRequest.builder().parentCode("a").build());
            allowRename.countDown();
            rename.get(10, TimeUnit.SECONDS);

            assertEquals("c", jdbc.queryForObject("SELECT parent_code FROM t_intent_node WHERE id = '1'", String.class));
            assertEquals("a", jdbc.queryForObject("SELECT parent_code FROM t_intent_node WHERE id = '2'", String.class));
            assertEquals("renamed", jdbc.queryForObject("SELECT name FROM t_intent_node WHERE id = '1'", String.class));
        } finally {
            allowRename.countDown();
            executor.shutdownNow();
        }
    }

    private boolean move(String id, String parentCode) {
        try {
            service.updateNode(id, IntentNodeUpdateRequest.builder().parentCode(parentCode).build());
            return true;
        } catch (ClientException rejected) {
            return false;
        }
    }
}
