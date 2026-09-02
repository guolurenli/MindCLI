package com.mindcli.platform.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonSupportTest {
    @Test
    void mapperIsSharedAndPrettyMapperIsIndented() throws Exception {
        assertSame(JsonSupport.mapper(), JsonSupport.mapper());
        assertTrue(JsonSupport.prettyMapper().writeValueAsString(java.util.Map.of("key", "value"))
                .contains("\n"));
    }

    @Test
    void newMapperIsIndependent() {
        ObjectMapper first = JsonSupport.newMapper();
        ObjectMapper second = JsonSupport.newMapper();
        assertNotSame(first, second);
        assertFalse(first == JsonSupport.mapper());
    }
}
