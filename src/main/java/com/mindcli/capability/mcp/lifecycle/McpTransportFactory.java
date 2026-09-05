package com.mindcli.capability.mcp.lifecycle;

import com.mindcli.capability.mcp.McpClient;
import com.mindcli.capability.mcp.McpServer;
import com.mindcli.capability.mcp.config.McpServerConfig;
import com.mindcli.capability.mcp.transport.MindCliStdioClientTransport;
import com.mindcli.platform.serialization.JsonSupport;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;

import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Creates official MCP SDK transports and wraps them in MindCLI's client facade. */
public final class McpTransportFactory {
    private final Path projectDir;

    public McpTransportFactory(Path projectDir) {
        this.projectDir = projectDir;
    }

    public McpClient create(McpServer server,
                            Consumer<McpClient> toolsChanged,
                            Runnable resourcesChanged,
                            Consumer<String> resourceUpdated) {
        McpServerConfig config = server.config();
        AtomicReference<McpClient> facade = new AtomicReference<>();
        McpSyncClient sdkClient;
        McpClientTransport sdkTransport;
        if (config.isHttp()) {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .header("Accept", "application/json, text/event-stream")
                    .header("Content-Type", "application/json");
            config.getHeaders().forEach(requestBuilder::header);
            HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(config.getUrl())
                    .jsonMapper(new JacksonMcpJsonMapper(JsonSupport.mapper()))
                    .requestBuilder(requestBuilder)
                    .connectTimeout(Duration.ofSeconds(30))
                    .customizeClient(builder -> builder.connectTimeout(Duration.ofSeconds(30)))
                    .openConnectionOnStartup(false)
                    .build();
            sdkTransport = transport;
            sdkClient = buildClient(transport,
                    ignored -> toolsChanged.accept(facade.get()),
                    ignored -> resourcesChanged.run(),
                    resourceUpdated);
        } else {
            ServerParameters parameters = ServerParameters.builder(resolveCommand(config.getCommand()))
                    .args(config.getArgs() == null ? List.of() : config.getArgs())
                    .env(config.getEnv() == null ? Map.of() : config.getEnv())
                    .build();
            MindCliStdioClientTransport transport = new MindCliStdioClientTransport(
                    parameters, new JacksonMcpJsonMapper(JsonSupport.mapper()), projectDir);
            sdkTransport = transport;
            sdkClient = buildClient(transport,
                    ignored -> toolsChanged.accept(facade.get()),
                    ignored -> resourcesChanged.run(),
                    resourceUpdated);
        }
        McpClient result = new McpClient(server.name(), sdkClient, sdkTransport,
                sdkTransport instanceof MindCliStdioClientTransport
                        ? () -> ((MindCliStdioClientTransport) sdkTransport).stderrLines() : List::of);
        facade.set(result);
        return result;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private McpSyncClient buildClient(McpClientTransport transport,
                                      Consumer toolsChanged,
                                      Consumer resourcesChanged,
                                      Consumer<String> resourceUpdated) {
        return io.modelcontextprotocol.client.McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(60))
                .initializationTimeout(Duration.ofSeconds(McpClient.initializeTimeoutSeconds()))
                .clientInfo(new McpSchema.Implementation("MindCLI", "1.0"))
                .toolsChangeConsumer(toolsChanged)
                .resourcesChangeConsumer(resourcesChanged)
                .resourcesUpdateConsumer(contents -> {
                    if (contents != null) contents.forEach(content -> resourceUpdated.accept(content.uri()));
                })
                .build();
    }

    private static String resolveCommand(String command) {
        if (command == null || !System.getProperty("os.name", "").toLowerCase().contains("win")
                || command.contains("/") || command.contains("\\")) return command;
        String lower = command.toLowerCase();
        if (lower.endsWith(".cmd") || lower.endsWith(".exe") || lower.endsWith(".bat")) return command;
        return command + ".cmd";
    }
}
