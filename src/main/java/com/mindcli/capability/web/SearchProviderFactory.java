package com.mindcli.capability.web;

import com.mindcli.platform.config.ConfigValueResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Locale;

/**
 * 按环境变量 / .env / 系统属性选择 SearchProvider 实现。
 *
 * 自动选择优先级（未显式 SEARCH_PROVIDER 时）：
 * <ol>
 *   <li>有 {@code GLM_API_KEY} → zhipu（智谱 Web Search，与 GLM 推理共用 Key，国内首选）</li>
 *   <li>有 {@code SERPAPI_KEY} → serpapi（国际通用，付费即开即用）</li>
 *   <li>有 {@code SEARXNG_URL} → searxng（开源自托管，免费）</li>
 *   <li>都没有 → 占位 zhipu provider，isReady() 为 false，由调用方提示用户</li>
 * </ol>
 *
 * 显式 {@code SEARCH_PROVIDER}（zhipu / serpapi / searxng）会跳过自动判断。
 *
 * 这里不做单例缓存，由调用方按需缓存（如 ToolRegistry 的 webSearchProvider 字段）。
 */
public final class SearchProviderFactory {

    private static final Logger log = LoggerFactory.getLogger(SearchProviderFactory.class);

    private SearchProviderFactory() {}

    public static SearchProvider create() {
        ConfigValueResolver values = new ConfigValueResolver(
                Path.of("."), Path.of(System.getProperty("user.home")));
        String provider = values.resolve("SEARCH_PROVIDER", null);
        String glmKey = values.resolve("GLM_API_KEY", null);
        String zhipuEngine = values.resolve("ZHIPU_SEARCH_ENGINE", null);
        String serpKey = values.resolve("SERPAPI_KEY", null);
        String searxngUrl = values.resolve("SEARXNG_URL", null);

        String chosen = pickProvider(provider, glmKey, serpKey, searxngUrl);
        log.info("SearchProvider chosen: {}", chosen);

        return switch (chosen) {
            case "searxng" -> new SearxngSearchProvider(searxngUrl);
            case "serpapi" -> new SerpApiSearchProvider(serpKey);
            default -> new ZhipuSearchProvider(glmKey, zhipuEngine);
        };
    }

    static String pickProvider(String explicit, String glmKey, String serpKey, String searxngUrl) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit.trim().toLowerCase(Locale.ROOT);
        }
        if (glmKey != null && !glmKey.isBlank()) {
            return "zhipu";
        }
        if (serpKey != null && !serpKey.isBlank()) {
            return "serpapi";
        }
        if (searxngUrl != null && !searxngUrl.isBlank()) {
            return "searxng";
        }
        return "zhipu"; // 默认占位（MindCLI 主要面向 GLM 用户），isReady() 会为 false
    }

}
