# Task 0: Migrate MCP protocol plumbing to the official Java SDK

Read the repository AGENTS.md first. This task replaces only MindCLI's hand-written MCP protocol/transport plumbing with the official Java MCP SDK; do not touch the unrelated dirty files or the RAG removal task.

Requirements:
- Modify pom.xml to add the official BOM and `io.modelcontextprotocol.sdk:mcp-core` plus `io.modelcontextprotocol.sdk:mcp-json-jackson2`, all version 2.0.1. Do not add Spring AI or snapshot versions.
- Inspect the actual 2.0.1 API before editing. Use official `McpSyncClient` or `McpAsyncClient`, `StdioClientTransport`, and `HttpClientStreamableHttpTransport` as appropriate.
- Preserve the existing public behavior consumed by McpServerManager and ToolRegistry: initialize, tools/list, tools/call, resources/list/read, prompts, configured environment/working directory, headers, timeouts, stderr/process status where the official API exposes them, and structured ToolOutput conversion.
- Keep MindCLI-specific config loading, server lifecycle/restart/background start, namespaced tool registration, policies, audit, resource locking, and runtime integration.
- Run `mvn -q -DskipTests compile` after dependency/API changes and run the MCP tests that compile.
- Do not delete old classes until all callers and tests are migrated; if a full replacement is too large or API mismatch is discovered, report the blocker instead of guessing.

Report contract: write a report to `.superpowers/sdd/task-0-report.md` containing changed files, exact Maven/API choices, tests run and output, and any concerns. Return only status, commit hash if made, one-line test summary, and concerns.
