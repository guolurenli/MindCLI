package com.mindcli.memory;

import java.time.Instant;
import java.util.Map;

/**
 * 记忆条目 - Memory 系统的基础数据单元
 */
public class MemoryEntry {
    private final String id;
    private final String name;       // 简短标题（10字以内），用于检索路由
    private final String content;    // 正文内容
    private final MemoryType type;
    private final Instant timestamp;
    private final Map<String, String> metadata;
    private final int tokenCount;

    public enum MemoryType {
        USER_PREFERENCE,  // 用户偏好：角色、习惯、技术栈偏好、沟通偏好、项目目标
        FEEDBACK,         // 反馈：用户纠正或确认的行为（含原因）
        PROJECT_FACT,     // 项目事实：决策、约定、里程碑、事故、环境配置
        REFERENCE         // 参考信息：外部系统、文档链接、第三方工具
    }

    public MemoryEntry(String id, String content, MemoryType type, Map<String, String> metadata, int tokenCount) {
        this(id, deriveName(content), content, type, Instant.now(), metadata, tokenCount);
    }

    public MemoryEntry(String id, String content, MemoryType type, Instant timestamp,
                       Map<String, String> metadata, int tokenCount) {
        this(id, deriveName(content), content, type, timestamp, metadata, tokenCount);
    }

    /**
     * 完整构造函数，包含 name 字段。
     * name 为简短标题，用于 LLM 路由检索；缺失时从 content 自动截取。
     */
    public MemoryEntry(String id, String name, String content, MemoryType type,
                       Map<String, String> metadata, int tokenCount) {
        this(id, name, content, type, Instant.now(), metadata, tokenCount);
    }

    public MemoryEntry(String id, String name, String content, MemoryType type, Instant timestamp,
                       Map<String, String> metadata, int tokenCount) {
        this.id = id;
        this.name = name != null && !name.isBlank() ? name : deriveName(content);
        this.content = content;
        this.type = type;
        this.timestamp = timestamp != null ? timestamp : Instant.now();
        this.metadata = metadata != null ? metadata : Map.of();
        this.tokenCount = tokenCount;
    }

    private static String deriveName(String content) {
        if (content == null || content.isBlank()) return "";
        return content.length() <= 80 ? content.replace("\n", " ")
                : content.substring(0, 80).replace("\n", " ") + "...";
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getContent() { return content; }
    public MemoryType getType() { return type; }
    public Instant getTimestamp() { return timestamp; }
    public Map<String, String> getMetadata() { return metadata; }
    public int getTokenCount() { return tokenCount; }

    /**
     * 粗略估算 token 数（中文约 1.5 字/token，英文约 4 字符/token）
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        long chineseChars = text.chars().filter(c -> c > 0x4E00 && c < 0x9FFF).count();
        long otherChars = text.length() - chineseChars;
        return (int) Math.ceil(chineseChars / 1.5 + otherChars / 4.0);
    }

    @Override
    public String toString() {
        return "[%s] %s: %s".formatted(type, id,
                name != null && !name.isBlank() ? name : (content.length() > 80 ? content.substring(0, 80) + "..." : content));
    }
}
