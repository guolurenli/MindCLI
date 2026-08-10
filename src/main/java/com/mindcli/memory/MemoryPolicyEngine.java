package com.mindcli.memory;

import java.util.Locale;
import java.util.regex.Pattern;

public final class MemoryPolicyEngine {
    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}\\b");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern AWS_KEY = Pattern.compile("(?i)\\bAKIA[0-9A-Z]{16}\\b");
    private static final Pattern GITHUB_TOKEN = Pattern.compile("(?i)\\bghp_[A-Za-z0-9]{20,}\\b");
    private static final Pattern OPENAI_KEY = Pattern.compile("(?i)\\bsk-[A-Za-z0-9_-]{20,}\\b");
    private static final Pattern GENERIC_SECRET = Pattern.compile(
            "(?i)\\b(?:api[_-]?key|token|secret|password|bearer)\\b\\s*[:=：-]\\s*[^\\s,;]{8,}");

    public MemoryPolicyDecision evaluate(String content, MemoryPolicyContext context) {
        String normalized = content == null ? "" : content.trim();
        if (normalized.isBlank()) {
            return MemoryPolicyDecision.deny("memory.empty", "记忆内容不能为空");
        }
        if (looksSensitive(normalized)) {
            return MemoryPolicyDecision.deny("memory.sensitive", "检测到敏感信息，拒绝写入长期记忆");
        }

        MemoryPolicyContext effective = context == null
                ? MemoryPolicyContext.manual("", "project")
                : context;
        String scope = normalizeScope(effective.scope());

        if (effective.autoExtractEnabled()) {
            return MemoryPolicyDecision.needApproval("memory.auto.proposal", "自动提取只生成候选，不直接写入长期记忆");
        }
        if (effective.externalContextUsed()) {
            return MemoryPolicyDecision.needApproval("memory.external.approval", "外部上下文参与写入，需要审批");
        }
        if ("global".equals(scope)) {
            return MemoryPolicyDecision.needApproval("memory.global.approval", "全局长期记忆需要审批");
        }
        return MemoryPolicyDecision.allow("memory.project.allow", "项目级显式保存允许直接写入");
    }

    public String describeRules() {
        return "项目级显式保存=ALLOW，global/auto-extract/external-context=NEED_APPROVAL，secret/token/PII=DENY";
    }

    private static boolean looksSensitive(String content) {
        return EMAIL.matcher(content).find()
                || PHONE.matcher(content).find()
                || AWS_KEY.matcher(content).find()
                || GITHUB_TOKEN.matcher(content).find()
                || OPENAI_KEY.matcher(content).find()
                || GENERIC_SECRET.matcher(content).find();
    }

    private static String normalizeScope(String scope) {
        if (scope == null) {
            return "project";
        }
        String normalized = scope.trim().toLowerCase(Locale.ROOT);
        return "global".equals(normalized) ? "global" : "project";
    }
}
