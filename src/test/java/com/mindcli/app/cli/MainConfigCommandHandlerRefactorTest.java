package com.mindcli.app.cli;

import com.mindcli.app.cli.command.ConfigCommandHandler;
import com.mindcli.platform.config.MindCliConfig;
import com.mindcli.platform.hitl.ApprovalRequest;
import com.mindcli.platform.hitl.ApprovalResult;
import com.mindcli.platform.hitl.HitlHandler;
import com.mindcli.platform.hitl.SwitchableHitlHandler;
import com.mindcli.platform.llm.LlmClient;
import com.mindcli.platform.render.Renderer;
import com.mindcli.platform.render.StatusInfo;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainConfigCommandHandlerRefactorTest {

    @Test
    void configHandlerMatchesMainParsingFacade() {
        String payload = "provider free-llm-api --base-url http://localhost:5173/v1 --api-key sk-secret --model auto --default";

        Main.ProviderConfigUpdate mainUpdate = Main.parseProviderConfigUpdate(payload);
        Main.ProviderConfigUpdate handlerUpdate = ConfigCommandHandler.parseProviderConfigUpdate(payload);

        assertEquals(mainUpdate, handlerUpdate);
        assertEquals("freellmapi", handlerUpdate.provider());
        assertEquals("auto", handlerUpdate.model());
        assertEquals(true, handlerUpdate.setDefault());
    }

    @Test
    void configHandlerSavesProviderConfigAndMasksSecret() {
        RecordingConfig config = new RecordingConfig();

        String output = ConfigCommandHandler.handleConfigCommand(config,
                "provider xfyun --base-url https://maas-api.cn-huabei-1.xf-yun.com/v2 --api-key sk-1234567890 --model Qwen3 --lora-id 0 --default");

        MindCliConfig.ProviderConfig providerConfig = config.getProviders().get("xfyun");
        assertNotNull(providerConfig);
        assertEquals("https://maas-api.cn-huabei-1.xf-yun.com/v2", providerConfig.getBaseUrl());
        assertEquals("sk-1234567890", providerConfig.getApiKey());
        assertEquals("Qwen3", providerConfig.getModel());
        assertEquals("0", providerConfig.getLoraId());
        assertEquals("xfyun", config.getDefaultProvider());
        assertEquals(true, config.saved);
        assertTrue(output.contains("apiKey: sk-1...7890"), output);
        assertTrue(output.contains("默认 provider 已设为 xfyun"), output);
    }

    @Test
    void configHandlerKeepsValidationMessages() {
        Main.ProviderConfigUpdate update = ConfigCommandHandler.parseProviderConfigUpdate(
                "provider freellmapi --lora-id 0");

        assertEquals("--lora-id 仅支持 xfyun provider", update.error());
        assertEquals(Main.handleConfigCommand(new RecordingConfig(), "provider unknown --model x"),
                ConfigCommandHandler.handleConfigCommand(new RecordingConfig(), "provider unknown --model x"));
    }

    @Test
    void configHandlerPaletteKeepsReadOnlyHints() {
        RecordingRenderer renderer = new RecordingRenderer(2);
        SwitchableHitlHandler hitlHandler = new SwitchableHitlHandler(new StubHitlHandler(true));

        ConfigCommandHandler.handleConfigPalette(renderer, new MindCliConfig(), null, hitlHandler, null);

        assertEquals("配置 / config", renderer.title);
        assertEquals(6, renderer.items.size());
        assertTrue(renderer.items.get(2).contains("HITL: ON"), renderer.items.toString());
        assertTrue(renderer.output().contains("切换 HITL"), renderer.output());
    }

    private static final class RecordingConfig extends MindCliConfig {
        private boolean saved;

        @Override
        public void save() {
            saved = true;
        }
    }

    private static final class RecordingRenderer implements Renderer {
        private final int selected;
        private final ByteArrayOutputStream sink = new ByteArrayOutputStream();
        private String title;
        private List<String> items = List.of();

        private RecordingRenderer(int selected) {
            this.selected = selected;
        }

        @Override
        public void start() {
        }

        @Override
        public void close() {
        }

        @Override
        public PrintStream stream() {
            return new PrintStream(sink, true, StandardCharsets.UTF_8);
        }

        @Override
        public void appendToolCalls(List<LlmClient.ToolCall> toolCalls) {
        }

        @Override
        public void appendDiff(String filePath, String before, String after) {
        }

        @Override
        public void updateStatus(StatusInfo status) {
        }

        @Override
        public ApprovalResult promptApproval(ApprovalRequest request) {
            return ApprovalResult.approve();
        }

        @Override
        public int openPalette(String title, List<String> items) {
            this.title = title;
            this.items = items;
            return selected;
        }

        private String output() {
            return sink.toString(StandardCharsets.UTF_8);
        }
    }

    private record StubHitlHandler(boolean enabled) implements HitlHandler {
        @Override
        public ApprovalResult requestApproval(ApprovalRequest request) {
            return ApprovalResult.approve();
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public void setEnabled(boolean enabled) {
        }

        @Override
        public boolean isApprovedAllByTool(String toolName) {
            return false;
        }

        @Override
        public boolean isApprovedAllByServer(String serverName) {
            return false;
        }

        @Override
        public void clearApprovedAll() {
        }

        @Override
        public void clearApprovedAllForServer(String serverName) {
        }
    }
}
