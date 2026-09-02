package com.mindcli.capability.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindcli.capability.web.HtmlExtractor;
import com.mindcli.capability.web.NetworkPolicy;
import com.mindcli.capability.web.SearchProvider;
import com.mindcli.capability.web.SearchResult;
import com.mindcli.capability.web.WebFetcher;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebToolExecutorTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void formatsSearchResultsFromInjectedProvider() throws Exception {
        SearchProvider provider = new SearchProvider() {
            public String name() { return "fake"; }
            public boolean isReady() { return true; }
            public String unavailableHint() { return "not ready"; }
            public List<SearchResult> search(String query, int topK) {
                return List.of(SearchResult.of(1, "MindCLI", "https://example.com/docs", "A useful result"));
            }
        };
        WebToolExecutor executor = executor(provider, (tool, args) -> null, tool -> null);

        String result = executor.search("architecture", 3);

        assertTrue(result.contains("[fake] architecture"));
        assertTrue(result.contains("1. MindCLI"));
        assertTrue(result.contains("https://example.com/docs"));
        assertTrue(result.contains("A useful result"));
    }

    @Test
    void rejectsUnsafeFetchBeforeCallingFetcher() {
        WebToolExecutor executor = executor(new UnavailableProvider(), (tool, args) -> null, tool -> null);

        String result = executor.fetch("http://localhost:8080", 1000);

        assertTrue(result.contains("网络访问被拒绝"));
    }

    @Test
    void routesStepSearchWhenModelAndMcpAreAvailable() {
        WebToolExecutor executor = executor(new UnavailableProvider(),
                (tool, args) -> ToolOutput.text("remote-result:" + args),
                tool -> {
                    try {
                        return MAPPER.readTree("{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},\"top_k\":{\"type\":\"integer\"}}}");
                    } catch (IOException e) {
                        return null;
                    }
                });

        String result = executor.search("latest", 2);

        assertTrue(result.contains("[StepSearch] latest"));
        assertTrue(result.contains("remote-result"));
        assertTrue(result.contains("\"top_k\":2"));
    }

    @Test
    void returnsEmptyQueryMessage() {
        WebToolExecutor executor = executor(new UnavailableProvider(), (tool, args) -> null, tool -> null);

        assertEquals("搜索关键词不能为空", executor.search(" ", 5));
        assertEquals("URL 不能为空", executor.fetch(" ", 100));
    }

    private WebToolExecutor executor(SearchProvider provider,
                                     WebToolExecutor.McpInvoker invoker,
                                     Function<String, JsonNode> schemaResolver) {
        return new WebToolExecutor(provider, new WebFetcher(), new HtmlExtractor(), new NetworkPolicy(),
                "step", "step-3.7-flash", invoker, schemaResolver);
    }

    private static final class UnavailableProvider implements SearchProvider {
        public String name() { return "fake"; }
        public boolean isReady() { return false; }
        public String unavailableHint() { return "provider unavailable"; }
        public List<SearchResult> search(String query, int topK) { return List.of(); }
    }
}
