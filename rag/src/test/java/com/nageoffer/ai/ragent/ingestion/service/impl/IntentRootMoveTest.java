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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.rag.controller.request.IntentNodeUpdateRequest;
import com.nageoffer.ai.ragent.rag.core.intent.IntentTreeCacheManager;
import com.nageoffer.ai.ragent.rag.dao.entity.IntentNodeDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.IntentNodeMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntentRootMoveTest {

    @Test
    void explicitNullParentMovesChildToRoot() throws Exception {
        IntentNodeDO saved = updateWithJson("{\"parentCode\":null}");

        assertNull(saved.getParentCode(), "The ROOT option sends parentCode:null");
    }

    @Test
    void omittedParentPreservesCurrentParent() throws Exception {
        IntentNodeDO saved = updateWithJson("{\"name\":\"renamed\"}");

        assertEquals("group", saved.getParentCode());
        assertEquals("renamed", saved.getName());
    }

    @Test
    void nonNullParentStillMovesToAnotherBranch() throws Exception {
        IntentNodeDO saved = updateWithJson("{\"parentCode\":\"other-group\"}");

        assertEquals("other-group", saved.getParentCode());
    }

    @Test
    void updateByIdSqlIncludesNullParentAssignment() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        new MybatisSqlSessionFactoryBuilder().build(configuration);
        configuration.addMapper(IntentNodeMapper.class);
        IntentNodeDO root = IntentNodeDO.builder().id("2").intentCode("child").parentCode(null).build();

        String sql = configuration.getMappedStatement(IntentNodeMapper.class.getName() + ".updateById")
                .getBoundSql(Map.of("et", root)).getSql();

        assertTrue(sql.matches("(?s).*parent_code\\s*=\\s*\\?.*"),
                "Moving to ROOT must write SQL NULL, generated SQL: " + sql);
    }

    private IntentNodeDO updateWithJson(String json) throws Exception {
        IntentNodeMapper mapper = mock(IntentNodeMapper.class);
        IntentNodeDO child = IntentNodeDO.builder().id("2").intentCode("child").name("child")
                .parentCode("group").level(1).kind(0).deleted(0).build();
        when(mapper.selectById("2")).thenReturn(child);
        IntentTreeServiceImpl service = new IntentTreeServiceImpl(mapper, mock(KnowledgeBaseMapper.class),
                mock(IntentTreeCacheManager.class), mock(BizChangeLogContext.class));
        IntentNodeUpdateRequest request = new ObjectMapper().readValue(json, IntentNodeUpdateRequest.class);

        service.updateNode("2", request);

        ArgumentCaptor<IntentNodeDO> updated = ArgumentCaptor.forClass(IntentNodeDO.class);
        verify(mapper).updateById(updated.capture());
        return updated.getValue();
    }
}
