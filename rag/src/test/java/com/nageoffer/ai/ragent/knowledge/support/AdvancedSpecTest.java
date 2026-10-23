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

package com.nageoffer.ai.ragent.knowledge.support;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AdvancedSpecTest {
    private final IngestionSpecCodec codec = new IngestionSpecCodec(new ObjectMapper());
    @Test void roundTripsAndRejectsUnknownOrInvalidMode() {
        var normalized = codec.normalize("{\"indexingMode\":\"PARENT_CHILD\"}");
        var spec = codec.read(normalized);
        assertTrue(spec.indexing().advanced());
        assertEquals(spec, codec.read(codec.normalize(normalized)));
        assertThrows(RuntimeException.class, () -> codec.normalize("{\"indexingMode\":\"UNKNOWN\"}"));
        assertThrows(RuntimeException.class, () -> codec.normalize("{\"indexingMode\":\"PARENT_CHILD\",\"maxChars\":-1}"));
        assertFalse(codec.read(null).indexing().advanced());
        assertFalse(codec.read("{\"version\":2}").indexing().advanced());
    }
}
