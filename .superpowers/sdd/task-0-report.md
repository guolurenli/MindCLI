# Task 0 Report: Official MCP SDK Migration

## Changed files

- `pom.xml`: imported `io.modelcontextprotocol.sdk:mcp-bom:2.0.1` and added `mcp-core` plus `mcp-json-jackson2` managed at 2.0.1.
- `src/main/java/com/mindcli/capability/mcp/McpClient.java`: added an official `McpSyncClient` facade path for initialize, tools/list, tools/call, resources/list/read/subscribe, prompts, typed schema conversion, structured tool output, and close; retained the old constructor/path for compatibility tests and callers.
- `src/main/java/com/mindcli/capability/mcp/McpServerManager.java`: production server startup now uses SDK `McpClient.sync`, `HttpClientStreamableHttpTransport`, and the MindCLI stdio transport; preserves configured headers, environment, project working directory, timeout settings, lifecycle, registration, and resource/tool change handling.
- `src/main/java/com/mindcli/capability/mcp/transport/MindCliStdioClientTransport.java`: official `StdioClientTransport` adapter that applies the project working directory and captures bounded stderr lines.

## Maven/API choices

The implementation targets SDK 2.0.1 APIs verified from the downloaded artifacts:

- `io.modelcontextprotocol.client.McpClient.sync(McpClientTransport)` and `McpSyncClient`.
- `io.modelcontextprotocol.client.transport.StdioClientTransport` with `ServerParameters`.
- `io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport` builder with request headers, connect timeout, and disabled eager connection.
- Typed `McpSchema` records for tools, resources, prompts, content, and tool results.

No Spring AI or snapshot dependency was added. The existing hand-written JSON-RPC and transport classes remain to avoid breaking existing public constructors/tests.

## Tests

- `mvn -q -DskipTests compile`: passed.
- `mvn -q -DskipTests=false -Dtest=McpClientTest,StdioTransportTest,StreamableHttpTransportTest test`: passed (13 MCP client tests plus legacy transport tests).
- `mvn -DskipTests=false -Dtest=McpServerManagerTest test`: blocked by the local Java runtime before any HTTP request. SDK `HttpClientStreamableHttpTransport` construction throws `java.io.UncheckedIOException: java.io.IOException: Unable to establish loopback connection`, rooted at `jdk.internal.net.http.HttpClientImpl` / `sun.nio.ch.PipeImpl` (`UnixDomainSockets.connect: Invalid argument`).

## Concerns

- The SDK keeps the stdio `Process` private and exposes stderr handling but no process PID accessor; official-path `McpClient.processId()` therefore returns `null`.
- Manager HTTP integration needs a JDK/runtime where `java.net.http.HttpClient` can establish its internal loopback pipe. This is an environment/runtime failure, not an MCP protocol response failure; the legacy OkHttp transport tests continue to pass.
- Existing JSON-RPC transport classes are intentionally not deleted in this task because compatibility callers/tests still instantiate them.
