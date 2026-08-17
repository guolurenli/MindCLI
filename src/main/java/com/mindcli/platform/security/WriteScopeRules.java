package com.mindcli.platform.security;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * writeScope 的统一规范化 / 匹配规则。
 *
 * <p>调度层（{@code TeamScheduler}）判断「多个写入 step 能不能并行」，工具层
 * （{@code ToolRegistry}）判断「当前写入路径是否越界」，必须共用同一套 scope
 * 语义，避免调度层说可并行、工具层却拒绝，或反过来。这里把 prefix / {@code /**} /
 * {@code /*} 的保守匹配模型集中到一处。</p>
 */
public final class WriteScopeRules {

    private WriteScopeRules() {
    }

    /**
     * 规范化 scope 列表：过滤空白、去首尾空格、去重。
     */
    public static List<String> normalizeScopes(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return List.of();
        }
        return scopes.stream()
                .filter(scope -> scope != null && !scope.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    /**
     * 格式化 scope 列表为人类可读字符串，用于错误信息与上下文文案。
     */
    public static String formatScopes(List<String> scopes) {
        return String.join(", ", normalizeScopes(scopes));
    }

    /**
     * 判断两个 scope 列表是否存在重叠（含父/子目录包含关系）。
     */
    public static boolean overlaps(List<String> left, List<String> right) {
        for (String a : normalizeScopes(left)) {
            for (String b : normalizeScopes(right)) {
                if (normalizedScopeOverlaps(a, b)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 判断 {@code path}（已规范化的绝对路径）是否落在任一 scope 之内。
     *
     * @param scopes 允许写入的 scope 列表（原始声明，未规范化）
     * @param root   项目根路径（用于把 path 转成相对路径）
     * @param path   待校验的绝对路径
     */
    public static boolean containsPath(List<String> scopes, Path root, Path path) {
        if (scopes == null || scopes.isEmpty() || root == null || path == null) {
            return false;
        }
        String rel = toSlash(root.relativize(path.toAbsolutePath().normalize()))
                .toLowerCase(Locale.ROOT);
        for (String scope : scopes) {
            String prefix = scopePrefix(scope);
            if (!prefix.isEmpty() && (rel.equals(prefix) || rel.startsWith(prefix + "/"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean normalizedScopeOverlaps(String left, String right) {
        String a = scopePrefix(left);
        String b = scopePrefix(right);
        if (a.isBlank() || b.isBlank()) {
            return false;
        }
        return a.equals(b)
                || b.startsWith(a + "/")
                || a.startsWith(b + "/");
    }

    /**
     * 把单个 scope 归一为「无尾随通配符、无尾随斜杠、统一分隔符、小写」的 prefix。
     */
    private static String scopePrefix(String scope) {
        if (scope == null) {
            return "";
        }
        String normalized = scope.trim()
                .replace('\\', '/')
                .replaceAll("/+", "/")
                .toLowerCase(Locale.ROOT);
        while (normalized.endsWith("/**") || normalized.endsWith("/*")) {
            normalized = normalized.substring(0, normalized.lastIndexOf('/'));
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String toSlash(Path path) {
        return path.toString().replace('\\', '/');
    }
}
