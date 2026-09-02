package com.mindcli.capability.tool;

import com.mindcli.capability.memory.MemoryWriteResult;

import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Executes the memory tools against injected memory services. */
public final class MemoryToolExecutor {
    private final BiFunction<String, String, MemoryWriteResult> memorySaver;
    private final BiFunction<String, Integer, String> memorySearcher;
    private final Function<String, String> memoryReader;

    public MemoryToolExecutor(BiFunction<String, String, MemoryWriteResult> memorySaver,
                              BiFunction<String, Integer, String> memorySearcher,
                              Function<String, String> memoryReader) {
        this.memorySaver = memorySaver;
        this.memorySearcher = memorySearcher;
        this.memoryReader = memoryReader;
    }

    public String save(Map<String, String> args) {
        String fact = args.get("fact");
        if (fact == null || fact.isBlank()) return "保存长期记忆失败: fact 不能为空";
        if (memorySaver == null) return "保存长期记忆失败: 记忆保存器未初始化";
        String normalized = fact.trim();
        String scope = "global".equalsIgnoreCase(args.get("scope")) ? "global" : "project";
        MemoryWriteResult result = memorySaver.apply(normalized, scope);
        if (result == null || result.message().isBlank()) {
            return MemoryWriteResult.legacyWritten(normalized, scope).message();
        }
        return result.message();
    }

    public String search(Map<String, String> args) {
        String query = args.get("query");
        if (query == null || query.isBlank()) return "检索长期记忆失败: query 不能为空";
        if (memorySearcher == null) return "检索长期记忆失败: 记忆检索器未初始化";
        int limit = Math.min(20, Math.max(1, parseInt(args.get("limit"), 5)));
        try {
            return memorySearcher.apply(query.trim(), limit);
        } catch (RuntimeException e) {
            return "检索长期记忆失败: " + e.getMessage();
        }
    }

    public String read(Map<String, String> args) {
        String id = args.get("id");
        if (id == null || id.isBlank()) return "读取长期记忆失败: id 不能为空";
        if (memoryReader == null) return "读取长期记忆失败: 记忆读取器未初始化";
        try {
            return memoryReader.apply(id.trim());
        } catch (RuntimeException e) {
            return "读取长期记忆失败: " + e.getMessage();
        }
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
