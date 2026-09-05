package com.mindcli.capability.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mindcli.capability.web.FetchResult;
import com.mindcli.capability.web.HtmlExtractor;
import com.mindcli.capability.web.NetworkPolicy;
import com.mindcli.capability.web.SearchProvider;
import com.mindcli.capability.web.SearchResult;
import com.mindcli.capability.web.WebFetcher;

import java.util.List;
import java.util.function.Function;

/** Executes bounded web search and fetch operations, including the StepSearch bridge. */
public final class WebToolExecutor {
    private static final ObjectMapper MAPPER = com.mindcli.platform.serialization.JsonSupport.mapper();
    private static final String STEP_SEARCH_TOOL = "mcp__step_search__web_search";
    private static final String STEP_FETCH_TOOL = "mcp__step_search__web_fetch";

    @FunctionalInterface
    public interface McpInvoker {
        ToolOutput invoke(String toolName, String argumentsJson);
    }

    private final SearchProvider searchProvider;
    private final WebFetcher webFetcher;
    private final HtmlExtractor htmlExtractor;
    private final NetworkPolicy networkPolicy;
    private final String currentProvider;
    private final String currentModel;
    private final McpInvoker mcpInvoker;
    private final Function<String, JsonNode> schemaResolver;

    public WebToolExecutor(SearchProvider searchProvider,
                           WebFetcher webFetcher,
                           HtmlExtractor htmlExtractor,
                           NetworkPolicy networkPolicy,
                           String currentProvider,
                           String currentModel,
                           McpInvoker mcpInvoker,
                           Function<String, JsonNode> schemaResolver) {
        this.searchProvider = searchProvider;
        this.webFetcher = webFetcher;
        this.htmlExtractor = htmlExtractor;
        this.networkPolicy = networkPolicy;
        this.currentProvider = currentProvider == null ? "" : currentProvider;
        this.currentModel = currentModel == null ? "" : currentModel;
        this.mcpInvoker = mcpInvoker;
        this.schemaResolver = schemaResolver;
    }

    public String search(String query, int topK) {
        if (query == null || query.isBlank()) {
            return "搜索关键词不能为空";
        }
        if (shouldPreferStepSearch()) {
            ObjectNode args = MAPPER.createObjectNode();
            args.put("query", query.trim());
            putIfStepToolAccepts(STEP_SEARCH_TOOL, args, topK, "top_k", "topK", "max_results", "num_results", "limit", "count");
            ToolOutput output = invokeMcp(STEP_SEARCH_TOOL, args.toString());
            if (isUsableMcpOutput(output)) {
                return "🔍 [StepSearch] " + query.trim() + "\n\n" + output.text().trim();
            }
        }
        if (searchProvider == null || !searchProvider.isReady()) {
            return "⚠️ " + (searchProvider == null ? "搜索服务未初始化" : searchProvider.unavailableHint());
        }
        try {
            List<SearchResult> results = searchProvider.search(query.trim(), topK);
            return formatSearchResults(searchProvider.name(), query, results);
        } catch (Exception e) {
            return "搜索失败 (" + searchProvider.name() + "): " + e.getMessage();
        }
    }

    public String fetch(String url, int maxChars) {
        if (url == null || url.isBlank()) {
            return "URL 不能为空";
        }
        String denyReason = networkPolicy.checkUrl(url);
        if (denyReason != null) {
            return "❌ 网络访问被拒绝: " + denyReason;
        }
        String rateReason = networkPolicy.acquire();
        if (rateReason != null) {
            return "❌ " + rateReason;
        }
        if (shouldPreferStepSearch()) {
            ObjectNode args = MAPPER.createObjectNode();
            args.put("url", url.trim());
            putIfStepToolAccepts(STEP_FETCH_TOOL, args, maxChars, "max_chars", "maxChars", "limit", "max_length", "maxLength");
            ToolOutput output = invokeMcp(STEP_FETCH_TOOL, args.toString());
            if (isUsableMcpOutput(output)) {
                return "🌐 [StepSearch] 抓取: " + url.trim() + "\n\n" + output.text().trim();
            }
        }
        try {
            WebFetcher.RawResponse raw = webFetcher.fetch(url.trim());
            HtmlExtractor.Extracted extracted = htmlExtractor.extract(raw.body(), raw.url());
            String markdown = extracted.markdown();
            int originalLength = markdown.length();
            boolean truncated = false;
            if (maxChars > 0 && markdown.length() > maxChars) {
                markdown = markdown.substring(0, maxChars);
                truncated = true;
            }
            return formatFetchResult(FetchResult.ok(raw.url(), extracted.title(), markdown, originalLength, truncated));
        } catch (Exception e) {
            return "抓取失败: " + e.getMessage();
        }
    }

    private boolean shouldPreferStepSearch() {
        return "step".equalsIgnoreCase(currentProvider)
                && currentModel.toLowerCase(java.util.Locale.ROOT).startsWith("step-3.7-flash")
                && mcpInvoker != null;
    }

    private ToolOutput invokeMcp(String toolName, String arguments) {
        try {
            return mcpInvoker == null ? null : mcpInvoker.invoke(toolName, arguments);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void putIfStepToolAccepts(String toolName, ObjectNode args, int value, String... names) {
        if (value <= 0 || schemaResolver == null) return;
        JsonNode schema = schemaResolver.apply(toolName);
        JsonNode properties = schema == null ? null : schema.path("properties");
        if (properties == null || !properties.isObject()) return;
        for (String name : names) {
            if (properties.has(name)) {
                args.put(name, value);
                return;
            }
        }
    }

    private boolean isUsableMcpOutput(ToolOutput output) {
        if (output == null || output.text() == null || output.text().isBlank()) return false;
        String text = output.text().trim();
        return !text.startsWith("[HITL]") && !text.startsWith("🛡️")
                && !text.startsWith("工具执行失败") && !text.startsWith("未知工具")
                && !text.startsWith("MCP 工具返回错误");
    }

    private String formatSearchResults(String providerName, String query, List<SearchResult> results) {
        if (results == null || results.isEmpty()) return "🔍 [" + providerName + "] " + query + "\n\n未找到相关结果。";
        StringBuilder sb = new StringBuilder("🔍 [").append(providerName).append("] ").append(query).append("\n\n");
        for (SearchResult result : results) {
            sb.append(result.position()).append(". ").append(result.title()).append("\n");
            if (!result.snippet().isBlank()) sb.append("   ").append(result.snippet().length() > 200 ? result.snippet().substring(0, 200) + "..." : result.snippet()).append("\n");
            if (!result.url().isBlank()) {
                sb.append("   🔗 ").append(result.url());
                if (!result.source().isBlank()) sb.append("  (").append(result.source()).append(")");
                sb.append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private String formatFetchResult(FetchResult result) {
        StringBuilder sb = new StringBuilder("🌐 抓取: ").append(result.url()).append("\n");
        if (!result.title().isBlank()) sb.append("📄 标题: ").append(result.title()).append("\n");
        if (result.bodyEmpty()) return sb.append("\n⚠️ ").append(result.hint()).append("\n").toString();
        sb.append("📏 正文 ").append(result.contentLength()).append(" 字符");
        if (result.truncated()) sb.append("（已截断）");
        return sb.append("\n\n---\n\n").append(result.markdown()).toString();
    }
}
