package com.mindcli.cli.command;

import com.mindcli.memory.LongTermMemory;
import com.mindcli.memory.MemoryEntry;
import com.mindcli.memory.MemoryManager;
import com.mindcli.memory.MemoryProposal;
import com.mindcli.memory.MemoryWriteResult;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

public final class MemoryCommandHandler {
    private MemoryCommandHandler() {
    }

    public record MemorySaveRequest(String fact, String scope) {
    }

    public static void printStatus(PrintStream out, MemoryManager memoryManager) {
        out.println("📋 记忆系统状态：");
        out.println(memoryManager.getSystemStatus());
        out.println("   当前项目作用域: " + memoryManager.getCurrentProject());
        out.println("   /memory policy - 查看记忆治理策略");
        out.println("   /memory proposals - 查看待确认候选记忆");
        out.println("   /memory export --audit - 导出记忆审计证据");
        out.println("   /memory approve <id> - 批准候选并写入长期记忆");
        out.println("   /memory reject <id> - 拒绝候选");
        out.println("   /memory list - 查看长期记忆");
        out.println("   /memory search <关键词> - 搜索当前项目可见长期记忆");
        out.println("   /memory delete <id> - 删除单条长期记忆");
        out.println("   /memory clear - 清空长期记忆");
        out.println("   /save <事实> - 保存项目级长期记忆；/save --global <事实> 保存全局记忆");
        out.println();
    }

    public static void printPolicy(PrintStream out, MemoryManager memoryManager) {
        out.println("📋 记忆治理策略：");
        out.println(memoryManager.getPolicyStatus());
        out.println();
    }

    public static void printProposals(PrintStream out, MemoryManager memoryManager) {
        out.println(formatProposals("📋 待确认候选记忆", memoryManager.listPendingMemoryProposals()));
        out.println();
    }

    public static void printAuditExport(PrintStream out, MemoryManager memoryManager) {
        Path exportsDir = Path.of(System.getProperty("user.home"), ".mindcli", "exports");
        try {
            Path exportFile = memoryManager.exportAudit(exportsDir);
            out.println("✅ 记忆审计已导出: " + exportFile.toAbsolutePath());
            out.println("   审计源: " + memoryManager.getMemoryAuditService().auditFile() + "\n");
        } catch (IOException e) {
            out.println("❌ 导出记忆审计失败: " + e.getMessage() + "\n");
        }
    }

    public static void printApprove(PrintStream out, MemoryManager memoryManager, String payload) {
        String id = payload == null ? "" : payload.trim();
        if (id.isBlank()) {
            out.println("❌ 请提供候选记忆 id，例如 /memory approve proposal-abcd1234\n");
        } else if (memoryManager.approveMemoryProposal(id)) {
            out.println("✅ 已批准候选记忆并写入长期记忆: " + id + "\n");
        } else {
            out.println("📭 未找到可批准的待确认候选: " + id + "\n");
        }
    }

    public static void printReject(PrintStream out, MemoryManager memoryManager, String payload) {
        String id = payload == null ? "" : payload.trim();
        if (id.isBlank()) {
            out.println("❌ 请提供候选记忆 id，例如 /memory reject proposal-abcd1234\n");
        } else if (memoryManager.rejectMemoryProposal(id)) {
            out.println("🚫 已拒绝候选记忆: " + id + "\n");
        } else {
            out.println("📭 未找到可拒绝的待确认候选: " + id + "\n");
        }
    }

    public static void printList(PrintStream out, MemoryManager memoryManager) {
        out.println(formatEntries("📋 长期记忆列表", memoryManager.listLongTerm()));
        out.println();
    }

    public static void printSearch(PrintStream out, MemoryManager memoryManager, String payload) {
        String query = payload == null ? "" : payload.trim();
        if (query.isBlank()) {
            out.println("❌ 请提供搜索关键词，例如 /memory search Chrome 登录态\n");
            return;
        }
        out.println(formatEntries("🔎 长期记忆搜索: " + query, memoryManager.searchLongTerm(query, 20)));
        out.println();
    }

    public static void printDelete(PrintStream out, MemoryManager memoryManager, String payload) {
        String id = payload == null ? "" : payload.trim();
        if (id.isBlank()) {
            out.println("❌ 请提供要删除的记忆 id，例如 /memory delete fact-abcd1234\n");
        } else if (memoryManager.deleteLongTerm(id)) {
            out.println("🗑️ 已删除长期记忆: " + id + "\n");
        } else {
            out.println("📭 未找到长期记忆: " + id + "\n");
        }
    }

    public static void printClear(PrintStream out, MemoryManager memoryManager) {
        memoryManager.clearLongTerm();
        out.println("🧹 长期记忆已清空\n");
        out.println();
    }

    public static void printSave(PrintStream out, MemoryManager memoryManager, String payload) {
        MemorySaveRequest saveRequest = parseSave(payload);
        if (saveRequest.fact().isEmpty()) {
            out.println("❌ 请提供要保存的内容，例如 /save 这个项目使用Java 17，或 /save --global 默认用中文回答\n");
            return;
        }
        MemoryWriteResult result = memoryManager.storeFact(saveRequest.fact(), saveRequest.scope());
        out.println(result.message() + "\n");
    }

    public static MemorySaveRequest parseSave(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.regionMatches(true, 0, "--global ", 0, 9)) {
            return new MemorySaveRequest(value.substring(9).trim(), "global");
        }
        if (value.equalsIgnoreCase("--global")) {
            return new MemorySaveRequest("", "global");
        }
        if (value.regionMatches(true, 0, "--project ", 0, 10)) {
            return new MemorySaveRequest(value.substring(10).trim(), "project");
        }
        if (value.equalsIgnoreCase("--project")) {
            return new MemorySaveRequest("", "project");
        }
        return new MemorySaveRequest(value, "project");
    }

    public static String formatEntries(String title, List<MemoryEntry> entries) {
        StringBuilder sb = new StringBuilder(title).append("：\n");
        if (entries == null || entries.isEmpty()) {
            return sb.append("📭 没有匹配的长期记忆。").toString();
        }
        for (MemoryEntry entry : entries) {
            String scope = LongTermMemory.scopeOf(entry);
            String project = entry.getMetadata().get("project");
            sb.append("- ")
                    .append(entry.getId())
                    .append(" [").append(scope).append("]");
            if ("project".equals(scope) && project != null && !project.isBlank()) {
                sb.append(" ").append(shortenPath(project));
            }
            sb.append(" · ").append(entry.getTimestamp()).append("\n")
                    .append("  ").append(entry.getContent()).append("\n");
        }
        return sb.toString().trim();
    }

    public static String formatProposals(String title, List<MemoryProposal> proposals) {
        StringBuilder sb = new StringBuilder(title).append("：\n");
        if (proposals == null || proposals.isEmpty()) {
            return sb.append("📭 没有待确认候选记忆。").toString();
        }
        for (MemoryProposal proposal : proposals) {
            sb.append("- ")
                    .append(proposal.id())
                    .append(" [").append(proposal.status()).append("] ")
                    .append(proposal.type())
                    .append(" · ").append(proposal.createdAt()).append("\n")
                    .append("  ").append(proposal.name()).append("\n")
                    .append("  ").append(preview(proposal.content(), 120)).append("\n");
        }
        return sb.toString().trim();
    }

    private static String preview(String content, int maxChars) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String normalized = content.replace('\n', ' ').trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars) + "...";
    }

    private static String shortenPath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        try {
            Path p = Path.of(path);
            int count = p.getNameCount();
            if (count <= 3) {
                return path;
            }
            return "..." + File.separator + p.subpath(count - 3, count);
        } catch (Exception e) {
            return path;
        }
    }
}
