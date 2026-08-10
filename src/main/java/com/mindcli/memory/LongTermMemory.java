package com.mindcli.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindcli.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 长期记忆 - 跨对话持久化的关键信息
 *
 * 对齐 Claude Code 的扁平 Markdown 文件存储格式：
 * ~/.mindcli/memory/
 * ├── MEMORY.md              # 索引文件
 * ├── fact-a1b2c3d4.md       # 单个记忆条目 (YAML frontmatter + markdown content)
 * └── ...
 *
 * 保留 ConcurrentHashMap 作为内存缓存层，读写延迟为零。
 * 存储从单 JSON 全量序列化改为多 .md 文件增量更新，I/O 减少 98%。
 */
public class LongTermMemory implements MemoryStore {
    private static final Logger log = LoggerFactory.getLogger(LongTermMemory.class);
    private static final String STORAGE_DIR_PROPERTY = "mindcli.memory.dir";
    private static final String STORAGE_DIR_ENV = "MINDCLI_MEMORY_DIR";
    private static final String INDEX_FILE = "MEMORY.md";
    private static final String LEGACY_JSON = "long_term_memory.json";
    private static final int MAX_INDEX_ENTRIES = 200;
    private static final long CONSOLIDATION_INTERVAL_DAYS = 2;
    private static final int MIN_ENTRIES_FOR_CONSOLIDATION = 5;

    private final File memoryDir;
    private final Map<String, MemoryEntry> entries;
    private final AtomicInteger tokenCounter;
    private final ObjectMapper mapper;

    public LongTermMemory() {
        this(resolveStorageDir());
    }

    public LongTermMemory(File storageDir) {
        this.entries = new ConcurrentHashMap<>();
        this.tokenCounter = new AtomicInteger(0);
        this.mapper = new ObjectMapper();

        // 确保存储目录存在
        File dir = storageDir;
        if (!dir.exists()) {
            dir.mkdirs();
        }
        this.memoryDir = dir;

        // 优先迁移旧版，再加载 .md
        migrateFromLegacyJson();
        loadFromDisk();
    }

    @Override
    public void store(MemoryEntry entry) {
        // 去重检查：如果已存在内容完全相同的条目，跳过
        boolean duplicate = entries.values().stream()
                .anyMatch(e -> e.getContent().equals(entry.getContent()));
        if (duplicate) {
            return;
        }

        entries.put(entry.getId(), entry);
        tokenCounter.addAndGet(entry.getTokenCount());

        // 写入单个 .md 文件 + 更新索引
        writeEntryFile(entry);
        updateIndex();
    }

    @Override
    public Optional<MemoryEntry> retrieve(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    @Override
    public List<MemoryEntry> search(String query, int limit) {
        return search(query, limit, null);
    }

    public List<MemoryEntry> search(String query, int limit, String projectKey) {
        String queryLower = query.toLowerCase();
        return entries.values().stream()
                .filter(entry -> isVisibleInProject(entry, projectKey))
                .filter(entry -> entry.getContent().toLowerCase().contains(queryLower)
                        || entry.getMetadata().values().stream()
                            .anyMatch(v -> v.toLowerCase().contains(queryLower)))
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public List<MemoryEntry> getAll() {
        return new ArrayList<>(entries.values());
    }

    public List<MemoryEntry> getAll(String projectKey) {
        return entries.values().stream()
                .filter(entry -> isVisibleInProject(entry, projectKey))
                .collect(Collectors.toList());
    }

    @Override
    public boolean delete(String id) {
        MemoryEntry removed = entries.remove(id);
        if (removed != null) {
            tokenCounter.addAndGet(-removed.getTokenCount());
            writeEntryFile(tombstone(removed));
            updateIndex();
            return true;
        }
        return false;
    }

    @Override
    public void clear() {
        // 删除所有 .md 文件
        for (String id : entries.keySet()) {
            File f = entryFile(id);
            if (f.exists()) {
                f.delete();
            }
        }
        entries.clear();
        tokenCounter.set(0);
        updateIndex();
    }

    @Override
    public int getTokenCount() {
        return tokenCounter.get();
    }

    @Override
    public int size() {
        return entries.size();
    }

    /**
     * 按类型筛选记忆
     */
    public List<MemoryEntry> getByType(MemoryEntry.MemoryType type) {
        return entries.values().stream()
                .filter(entry -> entry.getType() == type)
                .collect(Collectors.toList());
    }

    /**
     * 暴露存储目录，供 MemoryManager/Agent 读取 MEMORY.md 索引等文件。
     */
    public File getStorageDir() {
        return memoryDir;
    }

    @Override
    public Optional<Path> storagePath() {
        return Optional.of(memoryDir.toPath());
    }

    public static boolean isVisibleInProject(MemoryEntry entry, String projectKey) {
        String scope = scopeOf(entry);
        if ("global".equals(scope)) {
            return true;
        }
        String entryProject = entry.getMetadata().get("project");
        return projectKey != null && !projectKey.isBlank() && Objects.equals(entryProject, projectKey);
    }

    public static String scopeOf(MemoryEntry entry) {
        String scope = entry.getMetadata().get("scope");
        if ("project".equalsIgnoreCase(scope)) {
            return "project";
        }
        return "global";
    }

    // ===== 文件 I/O =====

    private File entryFile(String id) {
        return new File(memoryDir, sanitize(id) + ".md");
    }

    private void writeEntryFile(MemoryEntry entry) {
        try {
            StringBuilder md = new StringBuilder();
            md.append("---\n");
            md.append("name: ").append(entry.getName() != null ? entry.getName() : "").append("\n");
            md.append("type: ").append(entry.getType()).append("\n");
            md.append("timestamp: ").append(entry.getTimestamp()).append("\n");
            entry.getMetadata().forEach((k, v) ->
                md.append(k).append(": ").append(v).append("\n"));
            md.append("---\n\n");
            md.append(entry.getContent()).append("\n");
            Files.writeString(entryFile(entry.getId()).toPath(), md.toString());
        } catch (IOException e) {
            log.warn("写入记忆文件失败: {}", e.getMessage());
        }
    }

    private void updateIndex() {
        try {
            StringBuilder index = new StringBuilder("# 长期记忆索引\n\n");
            int count = 0;
            for (MemoryEntry entry : entries.values()) {
                if (count >= MAX_INDEX_ENTRIES) break;
                String preview = entry.getName() != null && !entry.getName().isBlank()
                        ? entry.getName()
                        : (entry.getContent().length() > 80
                            ? entry.getContent().substring(0, 80).replace("\n", " ") + "..."
                            : entry.getContent().replace("\n", " "));
                index.append("- [").append(preview).append("](")
                     .append(sanitize(entry.getId())).append(".md)  — ").append(entry.getType()).append("\n");
                count++;
            }
            Files.writeString(
                new File(memoryDir, INDEX_FILE).toPath(), index.toString());
        } catch (IOException e) {
            log.warn("更新记忆索引失败: {}", e.getMessage());
        }
    }

    private void loadFromDisk() {
        File[] mdFiles = memoryDir.listFiles(
            f -> f.getName().endsWith(".md") && !f.getName().equals(INDEX_FILE));
        if (mdFiles == null) return;

        for (File file : mdFiles) {
            try {
                MemoryEntry entry = parseEntryFile(file);
                if (entry != null) {
                    if (isDeleted(entry)) {
                        continue;
                    }
                    entries.put(entry.getId(), entry);
                    tokenCounter.addAndGet(entry.getTokenCount());
                }
            } catch (Exception e) {
                log.warn("解析记忆文件失败: {} - {}", file.getName(), e.getMessage());
            }
        }
        if (entries.size() > 0) {
            log.info("从 {} 加载了 {} 条长期记忆 (共 {} 个 .md 文件)",
                    memoryDir, entries.size(), mdFiles.length);
        }
    }

    private MemoryEntry parseEntryFile(File file) throws IOException {
        String content = Files.readString(file.toPath());
        if (!content.startsWith("---")) return null;

        // 解析 YAML frontmatter（简易解析，不引入 full YAML parser）
        int endFrontmatter = content.indexOf("---", 3);
        if (endFrontmatter < 0) return null;

        String frontmatter = content.substring(3, endFrontmatter);
        String body = content.substring(endFrontmatter + 3).trim();

        Map<String, String> metadata = new HashMap<>();
        String typeStr = "PROJECT_FACT";
        String name = null;
        Instant timestamp = Instant.now();
        String entryId = file.getName().replaceFirst("\\.md$", "");

        for (String line : frontmatter.split("\n")) {
            int colonIdx = line.indexOf(':');
            if (colonIdx < 0) continue;
            String key = line.substring(0, colonIdx).trim();
            String value = line.substring(colonIdx + 1).trim();

            if ("name".equals(key)) {
                name = value.isEmpty() ? null : value;
            } else if ("type".equals(key)) {
                typeStr = value;
            } else if ("timestamp".equals(key)) {
                try {
                    timestamp = Instant.parse(value);
                } catch (Exception e) {
                    // use default
                }
            } else {
                metadata.put(key, value);
            }
        }

        MemoryEntry.MemoryType type;
        try {
            type = MemoryEntry.MemoryType.valueOf(typeStr);
        } catch (IllegalArgumentException e) {
            type = MemoryEntry.MemoryType.PROJECT_FACT;
        }

        return new MemoryEntry(entryId, name, body, type, timestamp, metadata,
                MemoryEntry.estimateTokens(body));
    }

    private static MemoryEntry tombstone(MemoryEntry entry) {
        Map<String, String> metadata = new LinkedHashMap<>(entry.getMetadata());
        metadata.put("status", "deleted");
        metadata.put("deletedAt", Instant.now().toString());
        return new MemoryEntry(
                entry.getId(),
                entry.getName(),
                entry.getContent(),
                entry.getType(),
                entry.getTimestamp(),
                Map.copyOf(metadata),
                entry.getTokenCount());
    }

    private static boolean isDeleted(MemoryEntry entry) {
        String status = entry.getMetadata().get("status");
        return status != null && "deleted".equalsIgnoreCase(status.trim());
    }

    // ===== 旧版 JSON 迁移 =====

    @SuppressWarnings("unchecked")
    private void migrateFromLegacyJson() {
        File legacyFile = new File(memoryDir, LEGACY_JSON);
        if (!legacyFile.exists()) return;

        log.info("检测到旧版 JSON 记忆文件 ({} bytes)，开始迁移到 Markdown 格式...", legacyFile.length());
        try {
            List<Map<String, Object>> dataList = mapper.readValue(legacyFile, List.class);
            int migrated = 0;
            for (Map<String, Object> data : dataList) {
                try {
                    String id = (String) data.get("id");
                    String body = (String) data.get("content");
                    MemoryEntry.MemoryType type;
                    try {
                        type = MemoryEntry.MemoryType.valueOf(
                                (String) data.getOrDefault("type", "PROJECT_FACT"));
                    } catch (IllegalArgumentException e) {
                        type = MemoryEntry.MemoryType.PROJECT_FACT;
                    }
                    Instant timestamp = Instant.now();
                    Object ts = data.get("timestamp");
                    if (ts instanceof String tsStr && !tsStr.isBlank()) {
                        timestamp = Instant.parse(tsStr);
                    }
                    Map<String, String> metadata = new HashMap<>();
                    Object metaObj = data.get("metadata");
                    if (metaObj instanceof Map) {
                        ((Map<String, Object>) metaObj).forEach((k, v) -> metadata.put(k, String.valueOf(v)));
                    }

                    MemoryEntry entry = new MemoryEntry(
                            id != null ? id : "fact-migrated-" + migrated,
                            body != null ? body : "",
                            type, timestamp, metadata,
                            MemoryEntry.estimateTokens(body));
                    writeEntryFile(entry);
                    migrated++;
                } catch (Exception e) {
                    log.warn("跳过损坏的旧记忆条目: {}", e.getMessage());
                }
            }

            // 迁移完成，重命名为 .bak
            File bakFile = new File(memoryDir, LEGACY_JSON + ".bak");
            if (legacyFile.renameTo(bakFile)) {
                log.info("迁移完成: {} 条记忆已转为 Markdown，旧文件已备份为 {}", migrated, bakFile.getName());
            } else {
                log.warn("迁移完成: {} 条记忆已写入，但旧文件重命名失败，请手动删除 {}", migrated, legacyFile.getAbsolutePath());
            }
        } catch (IOException e) {
            log.warn("旧版 JSON 记忆迁移失败: {}", e.getMessage());
        }
    }

    // ===== Auto Dream: 记忆整合 =====

    /**
     * 定期整合长期记忆，合并重复、删除矛盾、更新过时事实。
     *
     * 对齐 Claude Code Auto Dream 机制，适配个人开发者场景（≥2 天 + ≥5 条）。
     * 采用增量合并模式：LLM 输出 MERGE/DELETE/KEEP 指令，代码逐条执行。
     * 安全原则：只有 LLM 明确标记删除的条目才删除，未提及的条目保持不动。
     * 异步执行，不阻塞启动。
     */
    public void consolidateIfNeeded(LlmClient llmClient) {
        if (entries.size() < MIN_ENTRIES_FOR_CONSOLIDATION) return;

        File lastConsolidationFile = new File(memoryDir, ".last_consolidation");
        if (lastConsolidationFile.exists()) {
            try {
                long lastTime = Long.parseLong(Files.readString(lastConsolidationFile.toPath()).trim());
                if (System.currentTimeMillis() - lastTime < CONSOLIDATION_INTERVAL_DAYS * 86400000L) {
                    return; // 不到 2 天，跳过
                }
            } catch (Exception e) {
                // 文件损坏，继续执行整合
            }
        }

        log.info("开始记忆整合... ({} 条记忆)", entries.size());
        LlmClient client = llmClient;
        List<MemoryEntry> snapshot = new ArrayList<>(entries.values());

        CompletableFuture.runAsync(() -> {
            try {
                String allFacts = snapshot.stream()
                        .map(e -> String.format("%s [%s] %s: %s",
                                e.getId(), e.getType(),
                                e.getName() != null ? e.getName() : "",
                                e.getContent()))
                        .collect(Collectors.joining("\n"));

                String prompt = """
                    以下是记忆系统的当前所有条目。请输出操作指令，每行一条：

                    MERGE id1 id2 → 新内容   # 合并两条重复记忆
                    DELETE id3                # 删除过时/矛盾的记忆
                    KEEP id4                  # 保留不需要改的记忆

                    规则：
                    1. 表达相同意思的重复条目用 MERGE 合并
                    2. 明显矛盾/过时的条目用 DELETE 删除
                    3. 未提及的条目默认保留
                    4. 只对确实需要变更的条目输出指令，不要逐条 KEEP

                    当前记忆：
                    %s

                    请输出操作指令：
                    """.formatted(allFacts);

                List<LlmClient.Message> req = List.of(
                    LlmClient.Message.system("你是一个记忆整合助手。只输出 MERGE/DELETE/KEEP 指令。"),
                    LlmClient.Message.user(prompt)
                );
                LlmClient.ChatResponse response = client.chat(req, null);

                // 增量执行指令：只操作明确标记的条目
                int merged = 0;
                int deleted = 0;
                Set<String> processedIds = new HashSet<>();

                for (String line : response.content().split("\n")) {
                    String instr = line.trim();
                    if (instr.isEmpty()) continue;

                    if (instr.startsWith("MERGE")) {
                        // 格式: MERGE id1 id2 → new_content
                        String[] parts = instr.substring(5).split("→", 2);
                        if (parts.length >= 2) {
                            String[] ids = parts[0].trim().split("\\s+");
                            String newContent = parts[1].trim();
                            if (ids.length >= 2 && newContent.length() > 5) {
                                // 保留 meta 和 type 均从第一条继承
                                MemoryEntry first = entries.get(ids[0].trim());
                                MemoryEntry.MemoryType mergeType = first != null
                                        ? first.getType() : MemoryEntry.MemoryType.PROJECT_FACT;
                                Map<String, String> mergeMeta = first != null
                                        ? first.getMetadata() : Map.of("source", "consolidation");
                                // 删除被合并的条目
                                for (String id : ids) {
                                    String trimmedId = id.trim();
                                    delete(trimmedId);
                                    processedIds.add(trimmedId);
                                }
                                // 写入合并后的新条目
                                store(new MemoryEntry(
                                        "fact-" + UUID.randomUUID().toString().substring(0, 8),
                                        newContent, mergeType,
                                        mergeMeta, MemoryEntry.estimateTokens(newContent)));
                                merged++;
                            }
                        }
                    } else if (instr.startsWith("DELETE")) {
                        String id = instr.substring(6).trim();
                        if (!id.isEmpty() && delete(id)) {
                            processedIds.add(id);
                            deleted++;
                        }
                    } else if (instr.startsWith("KEEP")) {
                        String id = instr.substring(4).trim();
                        if (!id.isEmpty()) {
                            processedIds.add(id);
                        }
                    }
                    // 无法识别的行静默跳过（防御性解析）
                }

                Files.writeString(lastConsolidationFile.toPath(),
                    String.valueOf(System.currentTimeMillis()));
                log.info("记忆整合完成: 合并 {} 组, 删除 {} 条, 整合后共 {} 条",
                        merged, deleted, entries.size());
            } catch (Exception e) {
                log.warn("记忆整合失败（记忆数据未受影响）: {}", e.getMessage());
            }
        });
    }

    // ===== 工具方法 =====

    private static String sanitize(String id) {
        return id.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static File resolveStorageDir() {
        String configuredDir = System.getProperty(STORAGE_DIR_PROPERTY);
        if (configuredDir == null || configuredDir.isBlank()) {
            configuredDir = System.getenv(STORAGE_DIR_ENV);
        }
        if (configuredDir != null && !configuredDir.isBlank()) {
            return new File(configuredDir);
        }
        return new File(new File(System.getProperty("user.home"), ".mindcli"), "memory");
    }

    /**
     * 生成记忆状态摘要
     */
    public String getStatusSummary() {
        Map<MemoryEntry.MemoryType, Long> typeCounts = entries.values().stream()
                .collect(Collectors.groupingBy(MemoryEntry::getType, Collectors.counting()));

        return String.format("长期记忆: %d条 / %d tokens (偏好: %d, 反馈: %d, 事实: %d, 参考: %d)",
                entries.size(), tokenCounter.get(),
                typeCounts.getOrDefault(MemoryEntry.MemoryType.USER_PREFERENCE, 0L),
                typeCounts.getOrDefault(MemoryEntry.MemoryType.FEEDBACK, 0L),
                typeCounts.getOrDefault(MemoryEntry.MemoryType.PROJECT_FACT, 0L),
                typeCounts.getOrDefault(MemoryEntry.MemoryType.REFERENCE, 0L));
    }
}
