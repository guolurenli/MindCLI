package com.mindcli.memory;

import com.mindcli.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 记忆提取器 - 一次 LLM 调用从对话历史中提取分类长期记忆
 *
 * 替代旧版 ContextCompressor (Map-Reduce) + ExplicitMemoryHints (规则匹配)。
 * 设计理念对齐 Claude Code：让 LLM 自己判断什么值得记住、属于什么类型，代码只做编排和排除。
 */
public class MemoryExtractor {
    private static final Logger log = LoggerFactory.getLogger(MemoryExtractor.class);
    private final LlmClient llmClient;
    private final LongTermMemory longTermMemory;

    /**
     * 上次提取时 conversationHistory 的消息数，用于增量提取。
     * - 对齐 Claude Code Stop hook：只传给 hook 本轮新增的 exchange
     * - trimConversationHistory 截断后，size 会变小，此时 reset 重算
     */
    private int lastExtractedSize = 0;

    /** 明确禁止提取为长期记忆的内容类型（对齐 Claude Code 的 Never Store 列表） */
    private static final Set<String> NEVER_STORE_KEYWORDS = Set.of(
            "代码模式", "设计模式", "编码规范", "代码风格",
            "架构约定", "项目文件路径", "文件结构",
            "git历史", "git log", "提交记录",
            "debug方案", "调试方法", "临时解决办法",
            "一次性文件名", "测试文件", "临时目录"
    );

    private static final String EXTRACT_PROMPT = """
        请回顾以上对话，提取对后续会话有价值的稳定信息。每条输出一行，格式为：
        [标题]: [类型] 详细内容

        标题应简短（10字以内），准确概括记忆要点。

        类型必须是以下四种之一：
        - USER_PREFERENCE: 用户偏好（角色、技术栈偏好、沟通偏好、目标）
        - FEEDBACK: 用户反馈（对你说法的纠正或确认，含原因）
        - PROJECT_FACT: 项目事实（重要决策、约定、里程碑、环境配置）
        - REFERENCE: 参考信息（外部系统链接、第三方文档、API 地址）

        提取原则：
        1. 只提取跨会话仍然成立的信息
        2. 不要提取本次任务的临时步骤、TODO、一次性文件名
        3. 不要提取猜测、推断、不确定的内容
        4. 不要提取代码模式、架构约定、编码规范（这些应在项目配置文件中管理）
        5. 不要提取可从代码/git 自动推导的信息（文件路径、提交历史）
        6. 如果用户明确说"记住"、"记一下"，应优先提取

        如果没有任何值得长期记住的内容，请输出 NO_FACTS。
        """;

    public MemoryExtractor(LlmClient llmClient, LongTermMemory longTermMemory) {
        this.llmClient = llmClient;
        this.longTermMemory = longTermMemory;
    }

    public void setLlmClient(LlmClient llmClient) {
        // Note: llmClient is effectively final in this design;
        // retained for API compatibility with setLlmClient pattern.
    }

    /**
     * 从对话历史中提取事实，直接存入 LongTermMemory（单轮提取）。
     * @deprecated 改为 {@link #extractFactsIncremental}，只传本轮新增消息
     */
    public void extractFacts(List<LlmClient.Message> conversationHistory) {
        extractFacts(conversationHistory, 1);
    }

    /**
     * 增量提取 —— 只处理本轮新增的对话，不重传整个 conversationHistory。
     *
     * 对齐 Claude Code Stop hook 语义：
     * - Stop hook 每次触发时只收到本轮新增的 exchange，不是整段历史重放
     * - 代码负责跟踪提取到哪了，每次只取 subList[lastExtractedSize, end)
     * - 如果 conversationHistory 被 trimConversationHistory 截断（size 变小），
     *   lastExtractedSize 自动重置到当前 size，下一轮提取从新的尾部重新开始
     */
    public synchronized void extractFactsIncremental(List<LlmClient.Message> conversationHistory) {
        if (conversationHistory == null || conversationHistory.isEmpty()) return;

        // trimConversationHistory 截断后 size 可能回退，此时重置位置
        if (lastExtractedSize > conversationHistory.size()) {
            lastExtractedSize = 0;
        }

        // 只取本轮新增的消息
        List<LlmClient.Message> newMessages = conversationHistory.subList(
                lastExtractedSize, conversationHistory.size());

        // 新增内容太少，攒着下次一起提（至少 2 条 user 消息才算一轮有效对话）
        long newUserCount = newMessages.stream().filter(m -> "user".equals(m.role())).count();
        if (newUserCount < 2) return;

        // 标记本次提取位置
        lastExtractedSize = conversationHistory.size();

        String dialogue = newMessages.stream()
                .filter(m -> "user".equals(m.role()) || "assistant".equals(m.role()))
                .map(m -> m.role().toUpperCase() + ": " + truncate(m.content(), 2000))
                .reduce("", (a, b) -> a + "\n\n" + b);

        if (dialogue.length() < 500) return;

        try {
            String result = doExtractRound(dialogue, null, 0, 1);
            if (result != null && !"NO_FACTS".equals(result.trim())) {
                storeExtractedFacts(result);
            }
        } catch (IOException e) {
            log.warn("增量事实提取失败，跳过: {}", e.getMessage());
        }
    }

    /**
     * 多轮提取：对于长对话（>20 条消息），最多执行 maxTurns 轮 LLM 调用，
     * 每轮回读确认后再写入。短对话退化为单轮。
     *
     * @param conversationHistory ReAct 主循环的消息历史
     * @param maxTurns            最大轮次（建议 1-3）
     */
    public void extractFacts(List<LlmClient.Message> conversationHistory, int maxTurns) {
        if (conversationHistory == null || conversationHistory.size() < 4) {
            return;
        }

        String dialogue = conversationHistory.stream()
                .filter(m -> "user".equals(m.role()) || "assistant".equals(m.role()))
                .map(m -> m.role().toUpperCase() + ": " + truncate(m.content(), 2000))
                .reduce("", (a, b) -> a + "\n\n" + b);

        if (dialogue.length() < 500) return;

        // 短对话单轮即可，长对话使用多轮
        int turns = Math.min(maxTurns, conversationHistory.size() > 20 ? 3 : 1);

        try {
            String result = null;
            for (int i = 0; i < turns; i++) {
                result = doExtractRound(dialogue, result, i, turns);
                if (result == null || "NO_FACTS".equals(result.trim())) {
                    return;
                }
            }
            // 最终轮结果已包含确认后的事实，直接解析存储
            if (result != null && !"NO_FACTS".equals(result.trim())) {
                storeExtractedFacts(result);
            }
        } catch (IOException e) {
            log.warn("事实提取 LLM 调用失败，跳过本轮记忆提取: {}", e.getMessage());
        }
    }

    private String doExtractRound(String dialogue, String prevResult, int round, int totalRounds)
            throws IOException {
        String prompt;
        if (round == 0) {
            prompt = EXTRACT_PROMPT + "\n\n对话：\n" + dialogue;
        } else {
            prompt = """
                上一轮你提取了以下候选事实：
                %s

                请逐条审视：
                1. 这条是持久事实还是临时指令？（临时指令应删除）
                2. 这条是否属于被禁止的类型（代码模式/架构约定/文件路径等）？（是则应删除）
                3. 同一条事实是否有更准确的表述？（有则修正）

                输出最终确认的事实（格式同上），不再需要的条目直接移除。
                """.formatted(prevResult);
        }

        List<LlmClient.Message> request = List.of(
                LlmClient.Message.system("你是一个信息提取助手，只输出值得长期记住的事实。"),
                LlmClient.Message.user(prompt)
        );

        return llmClient.chat(request, null).content();
    }

    private void storeExtractedFacts(String result) {
        int count = 0;
        for (String line : result.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || "NO_FACTS".equals(trimmed)) continue;

            // 解析格式：[标题]: [类型] 详细内容
            String name = null;
            MemoryEntry.MemoryType type = MemoryEntry.MemoryType.PROJECT_FACT;
            String factBody = trimmed;

            // 尝试解析 [标题]: [类型] 内容
            if (trimmed.startsWith("[") && trimmed.contains("]: [")) {
                int nameEnd = trimmed.indexOf("]: [");
                if (nameEnd > 1) {
                    name = trimmed.substring(1, nameEnd).trim();
                    int typeEnd = trimmed.indexOf("] ", nameEnd + 4);
                    if (typeEnd > 0) {
                        String typeStr = trimmed.substring(nameEnd + 4, typeEnd).trim();
                        type = parseType(typeStr);
                        factBody = trimmed.substring(typeEnd + 2).trim();
                    }
                }
            } else {
                // 兼容旧格式: - [类型: ...] 内容
                factBody = trimmed.replaceFirst("^- \\[.*?\\] ", "").trim();
                if (factBody.isEmpty() || factBody.length() < 5) continue;
            }

            if (factBody.isEmpty() || factBody.length() < 5) continue;
            if (isNeverStore(factBody)) {
                log.debug("跳过排除类事实: {}", factBody.substring(0, Math.min(50, factBody.length())));
                continue;
            }

            MemoryEntry entry = new MemoryEntry(
                    "fact-" + UUID.randomUUID().toString().substring(0, 8),
                    name,
                    factBody,
                    type,
                    Map.of("source", "extractor"),
                    MemoryEntry.estimateTokens(factBody)
            );
            longTermMemory.store(entry);
            count++;
        }
        if (count > 0) {
            log.info("提取了 {} 条事实到长期记忆", count);
        }
    }

    private static MemoryEntry.MemoryType parseType(String typeStr) {
        if (typeStr == null) return MemoryEntry.MemoryType.PROJECT_FACT;
        return switch (typeStr.trim().toUpperCase()) {
            case "USER_PREFERENCE", "USER" -> MemoryEntry.MemoryType.USER_PREFERENCE;
            case "FEEDBACK" -> MemoryEntry.MemoryType.FEEDBACK;
            case "PROJECT_FACT", "FACT", "PROJECT" -> MemoryEntry.MemoryType.PROJECT_FACT;
            case "REFERENCE" -> MemoryEntry.MemoryType.REFERENCE;
            default -> MemoryEntry.MemoryType.PROJECT_FACT;
        };
    }

    /** 检查事实是否属于禁止存储的类型 */
    static boolean isNeverStore(String fact) {
        String lower = fact.toLowerCase();
        return NEVER_STORE_KEYWORDS.stream().anyMatch(lower::contains);
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "...");
    }
}
