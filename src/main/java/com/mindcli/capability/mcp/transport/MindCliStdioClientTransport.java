package com.mindcli.capability.mcp.transport;

import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;

/** Official MCP stdio transport with MindCLI's configured workspace and stderr ring. */
public final class MindCliStdioClientTransport extends StdioClientTransport {
    private static final int MAX_STDERR_LINES = 200;

    private final Path workingDirectory;
    private final ArrayDeque<String> stderr = new ArrayDeque<>();

    public MindCliStdioClientTransport(ServerParameters parameters, McpJsonMapper mapper, Path workingDirectory) {
        super(parameters, mapper);
        this.workingDirectory = workingDirectory;
        setStdErrorHandler(this::appendStderr);
    }

    @Override
    protected ProcessBuilder getProcessBuilder() {
        ProcessBuilder builder = super.getProcessBuilder();
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        return builder;
    }

    public synchronized List<String> stderrLines() {
        return List.copyOf(stderr);
    }

    private synchronized void appendStderr(String line) {
        if (line == null) return;
        while (stderr.size() >= MAX_STDERR_LINES) stderr.removeFirst();
        stderr.addLast(line);
    }
}
