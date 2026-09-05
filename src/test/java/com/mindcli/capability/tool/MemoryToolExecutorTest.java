package com.mindcli.capability.tool;

import com.mindcli.capability.memory.MemoryWriteResult;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryToolExecutorTest {

    @Test
    void savesNormalizedFactWithProjectScopeByDefault() {
        AtomicReference<String> fact = new AtomicReference<>();
        AtomicReference<String> scope = new AtomicReference<>();
        MemoryToolExecutor executor = new MemoryToolExecutor(
                (value, selectedScope) -> {
                    fact.set(value);
                    scope.set(selectedScope);
                    return MemoryWriteResult.written(null, "test", "saved");
                }, null, null);

        assertEquals("saved", executor.save(Map.of("fact", "  keep this  ")));
        assertEquals("keep this", fact.get());
        assertEquals("project", scope.get());
    }

    @Test
    void searchesWithBoundedLimitAndHandlesReaderErrors() {
        AtomicReference<Integer> limit = new AtomicReference<>();
        MemoryToolExecutor executor = new MemoryToolExecutor(null,
                (query, selectedLimit) -> {
                    limit.set(selectedLimit);
                    return "matches";
                }, id -> {
                    throw new IllegalStateException("missing");
                });

        assertEquals("matches", executor.search(Map.of("query", "api", "limit", "99")));
        assertEquals(20, limit.get());
        assertTrue(executor.read(Map.of("id", "m1")).contains("读取长期记忆失败: missing"));
    }

    @Test
    void reportsMissingDependenciesAndArguments() {
        MemoryToolExecutor executor = new MemoryToolExecutor(null, null, null);

        assertEquals("保存长期记忆失败: fact 不能为空", executor.save(Map.of()));
        assertEquals("检索长期记忆失败: query 不能为空", executor.search(Map.of()));
        assertEquals("读取长期记忆失败: id 不能为空", executor.read(Map.of()));
        assertEquals("保存长期记忆失败: 记忆保存器未初始化", executor.save(Map.of("fact", "fact")));
        assertEquals("检索长期记忆失败: 记忆检索器未初始化", executor.search(Map.of("query", "q")));
        assertEquals("读取长期记忆失败: 记忆读取器未初始化", executor.read(Map.of("id", "id")));
    }
}
