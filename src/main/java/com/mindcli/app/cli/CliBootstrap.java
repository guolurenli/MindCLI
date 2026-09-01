package com.mindcli.app.cli;

import com.mindcli.platform.config.ConfigValueResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

final class CliBootstrap {
    private static final String LOG_DIR_PROPERTY = "mindcli.log.dir";
    private static final String LOG_LEVEL_PROPERTY = "mindcli.log.level";
    private static final String LOG_MAX_HISTORY_PROPERTY = "mindcli.log.maxHistory";
    private static final String LOG_MAX_FILE_SIZE_PROPERTY = "mindcli.log.maxFileSize";
    private static final String LOG_TOTAL_SIZE_CAP_PROPERTY = "mindcli.log.totalSizeCap";
    private static final String DEFAULT_CHROME_DEVTOOLS_MCP_JSON = """
            {
              "mcpServers": {
                "chrome-devtools": {
                  "command": "npx",
                  "args": ["-y", "chrome-devtools-mcp@latest", "--isolated=true"]
                }
              }
            }
            """;

    private CliBootstrap() {
    }

    static void configureAwtForCli() {
        if (!isMacOs()) {
            return;
        }
        System.setProperty("java.awt.headless", "true");
    }

    static boolean isMacOs() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    static void configureLogging() {
        configureLogProperty(LOG_DIR_PROPERTY, "MINDCLI_LOG_DIR",
                Path.of(System.getProperty("user.home"), ".mindcli", "logs").toString());
        configureLogProperty(LOG_LEVEL_PROPERTY, "MINDCLI_LOG_LEVEL", "INFO");
        configureLogProperty(LOG_MAX_HISTORY_PROPERTY, "MINDCLI_LOG_MAX_HISTORY", "7");
        configureLogProperty(LOG_MAX_FILE_SIZE_PROPERTY, "MINDCLI_LOG_MAX_FILE_SIZE", "10MB");
        configureLogProperty(LOG_TOTAL_SIZE_CAP_PROPERTY, "MINDCLI_LOG_TOTAL_SIZE_CAP", "100MB");

        try {
            Files.createDirectories(Path.of(System.getProperty(LOG_DIR_PROPERTY)));
        } catch (IOException e) {
            System.err.println("⚠️ 创建日志目录失败: " + e.getMessage());
        }
    }

    static Duration mcpStartupWait() {
        int seconds = resolver().resolveInt(
                "mindcli.mcp.startup.wait.seconds", "MINDCLI_MCP_STARTUP_WAIT_SECONDS", 8);
        return Duration.ofSeconds(seconds > 0 ? seconds : 8);
    }

    static String appendStartupNote(String current, String next) {
        if (next == null || next.isBlank()) {
            return current == null ? "" : current;
        }
        if (current == null || current.isBlank()) {
            return next;
        }
        return current + "\n" + next;
    }

    static Main.McpConfigBootstrapResult ensureDefaultMcpConfig(Path userHome) throws IOException {
        Path configFile = userHome.resolve(".mindcli").resolve("mcp.json");
        if (Files.notExists(configFile)) {
            Files.createDirectories(configFile.getParent());
            Files.writeString(configFile, DEFAULT_CHROME_DEVTOOLS_MCP_JSON);
            return new Main.McpConfigBootstrapResult(true,
                    "✅ 已创建默认 MCP 配置: " + configFile
                            + "\n   默认启用 chrome-devtools（isolated 模式）。");
        }
        String content = Files.readString(configFile);
        if (!content.contains("\"chrome-devtools\"")) {
            return new Main.McpConfigBootstrapResult(false,
                    "ℹ️ 检测到 ~/.mindcli/mcp.json 未配置 chrome-devtools，建议参考 README 添加浏览器 MCP server。");
        }
        return new Main.McpConfigBootstrapResult(false, "");
    }

    static String loadConfigValue(String key, String defaultValue) {
        return resolver().resolve(key, defaultValue);
    }

    private static void configureLogProperty(String propertyName, String envKey, String defaultValue) {
        String configuredValue = resolver().resolve(propertyName, envKey, defaultValue);
        if (configuredValue != null && !configuredValue.isBlank()) {
            if (LOG_DIR_PROPERTY.equals(propertyName)) {
                configuredValue = expandHome(configuredValue.trim());
            }
            System.setProperty(propertyName, configuredValue.trim());
        }
    }

    private static String expandHome(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (value.equals("~")) {
            return System.getProperty("user.home");
        }
        if (value.startsWith("~/")) {
            return Path.of(System.getProperty("user.home"), value.substring(2)).toString();
        }
        return value;
    }

    private static ConfigValueResolver resolver() {
        return new ConfigValueResolver(Path.of("."), Path.of(System.getProperty("user.home")));
    }
}
