package com.mindcli.platform.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class MindCliConfig {

    private static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".mindcli");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.json");
    private static final ObjectMapper mapper = com.mindcli.platform.serialization.JsonSupport.prettyMapper();
    private static final ConfigValueResolver CONFIG_VALUES = new ConfigValueResolver(
            Path.of("."), Path.of(System.getProperty("user.home")));

    private String defaultProvider = "glm";
    private Map<String, ProviderConfig> providers = new LinkedHashMap<>();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProviderConfig {
        private String apiKey;
        private String baseUrl;
        private String model;
        private String loraId;
        private double temperature = 0.7;  // 默认温度
        private int maxTokens = 8192;      // 默认最大 token 数

        public ProviderConfig() {}

        public ProviderConfig(String apiKey, String baseUrl, String model) {
            this.apiKey = apiKey;
            this.baseUrl = baseUrl;
            this.model = model;
        }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getLoraId() { return loraId; }
        public void setLoraId(String loraId) { this.loraId = loraId; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    }

    public String getDefaultProvider() { return defaultProvider; }
    public void setDefaultProvider(String defaultProvider) { this.defaultProvider = defaultProvider; }
    public Map<String, ProviderConfig> getProviders() { return providers; }
    public void setProviders(Map<String, ProviderConfig> providers) { this.providers = providers; }

    public String getApiKey(String provider) {
        ProviderConfig providerConfig = providers.get(provider);
        if (providerConfig != null && providerConfig.getApiKey() != null && !providerConfig.getApiKey().isBlank()) {
            return providerConfig.getApiKey();
        }
        return loadApiKeyFromEnv(provider);
    }

    public String getModel(String provider) {
        ProviderConfig providerConfig = providers.get(provider);
        if (providerConfig != null && providerConfig.getModel() != null && !providerConfig.getModel().isBlank()) {
            return providerConfig.getModel();
        }
        return loadModelFromEnv(provider);
    }

    public String getBaseUrl(String provider) {
        ProviderConfig providerConfig = providers.get(provider);
        if (providerConfig != null && providerConfig.getBaseUrl() != null && !providerConfig.getBaseUrl().isBlank()) {
            return providerConfig.getBaseUrl();
        }
        return loadBaseUrlFromEnv(provider);
    }

    public String getLoraId(String provider) {
        ProviderConfig providerConfig = providers.get(provider);
        if (providerConfig != null && providerConfig.getLoraId() != null && !providerConfig.getLoraId().isBlank()) {
            return providerConfig.getLoraId();
        }
        return loadLoraIdFromEnv(provider);
    }

    public static MindCliConfig load() {
        if (Files.exists(CONFIG_FILE)) {
            try {
                return mapper.readValue(CONFIG_FILE.toFile(), MindCliConfig.class);
            } catch (IOException e) {
                System.err.println("⚠️ 配置文件读取失败，使用默认配置: " + e.getMessage());
            }
        }
        return new MindCliConfig();
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_DIR);
            mapper.writeValue(CONFIG_FILE.toFile(), this);
        } catch (IOException e) {
            System.err.println("⚠️ 配置保存失败: " + e.getMessage());
        }
    }

    private static String loadModelFromEnv(String provider) {
        String envKey = switch (provider.toLowerCase()) {
            case "glm" -> "GLM_MODEL";
            case "deepseek" -> "DEEPSEEK_MODEL";
            case "kimi" -> "KIMI_MODEL";
            case "freellmapi" -> "FREELLMAPI_MODEL";
            case "xfyun" -> "XFYUN_MAAS_MODEL";
            default -> provider.toUpperCase() + "_MODEL";
        };

        String value = CONFIG_VALUES.resolve(envKey, null);
        if (value != null) return value;

        if ("kimi".equalsIgnoreCase(provider)) {
            return CONFIG_VALUES.resolve("MOONSHOT_MODEL", null);
        }

        if ("xfyun".equalsIgnoreCase(provider)) {
            return CONFIG_VALUES.resolve("XFYUN_MODEL", null);
        }

        return null;
    }

    private static String loadApiKeyFromEnv(String provider) {
        String envKey = switch (provider.toLowerCase()) {
            case "glm" -> "GLM_API_KEY";
            case "deepseek" -> "DEEPSEEK_API_KEY";
            case "step" -> "STEP_API_KEY";
            case "kimi" -> "KIMI_API_KEY";
            case "freellmapi" -> "FREELLMAPI_API_KEY";
            case "xfyun" -> "XFYUN_MAAS_API_KEY";
            default -> provider.toUpperCase() + "_API_KEY";
        };

        String value = CONFIG_VALUES.resolve(envKey, null);
        if (value != null) return value;

        if ("kimi".equalsIgnoreCase(provider)) {
            return CONFIG_VALUES.resolve("MOONSHOT_API_KEY", null);
        }

        if ("xfyun".equalsIgnoreCase(provider)) {
            return CONFIG_VALUES.resolve("XFYUN_API_KEY", null);
        }

        return null;
    }

    private static String loadBaseUrlFromEnv(String provider) {
        String envKey = switch (provider.toLowerCase()) {
            case "step" -> "STEP_BASE_URL";
            case "kimi" -> "KIMI_BASE_URL";
            case "freellmapi" -> "FREELLMAPI_BASE_URL";
            case "xfyun" -> "XFYUN_MAAS_BASE_URL";
            default -> provider.toUpperCase() + "_BASE_URL";
        };

        String value = CONFIG_VALUES.resolve(envKey, null);
        if (value != null) return value;

        if ("kimi".equalsIgnoreCase(provider)) {
            return CONFIG_VALUES.resolve("MOONSHOT_BASE_URL", null);
        }

        if ("xfyun".equalsIgnoreCase(provider)) {
            return CONFIG_VALUES.resolve("XFYUN_BASE_URL", null);
        }

        return null;
    }

    private static String loadLoraIdFromEnv(String provider) {
        if (!"xfyun".equalsIgnoreCase(provider)) {
            return null;
        }

        String value = CONFIG_VALUES.resolve("XFYUN_MAAS_LORA_ID", null);
        return value != null ? value : CONFIG_VALUES.resolve("XFYUN_LORA_ID", null);
    }
}
