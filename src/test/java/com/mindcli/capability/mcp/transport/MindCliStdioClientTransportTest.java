package com.mindcli.capability.mcp.transport;

import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MindCliStdioClientTransportTest {

    @Test
    void appliesConfiguredWorkingDirectoryToOfficialProcessBuilder() throws Exception {
        Path directory = Files.createTempDirectory("mindcli-stdio-cwd");
        ServerParameters parameters = ServerParameters.builder("java").build();
        MindCliStdioClientTransport transport = new MindCliStdioClientTransport(
                parameters, new JacksonMcpJsonMapper(new com.fasterxml.jackson.databind.ObjectMapper()), directory);
        try {
            Method method = MindCliStdioClientTransport.class.getDeclaredMethod("getProcessBuilder");
            method.setAccessible(true);
            ProcessBuilder builder = (ProcessBuilder) method.invoke(transport);
            assertEquals(directory.toFile(), builder.directory());
        } finally {
            transport.close();
        }
    }

    @Test
    void keepsOnlyTheLatestTwoHundredStderrLines() throws Exception {
        ServerParameters parameters = ServerParameters.builder("java").build();
        MindCliStdioClientTransport transport = new MindCliStdioClientTransport(
                parameters, new JacksonMcpJsonMapper(new com.fasterxml.jackson.databind.ObjectMapper()), null);
        try {
            Method method = MindCliStdioClientTransport.class.getDeclaredMethod("appendStderr", String.class);
            method.setAccessible(true);
            for (int i = 0; i < 250; i++) {
                method.invoke(transport, "line-" + i);
            }
            List<String> lines = transport.stderrLines();
            assertEquals(200, lines.size());
            assertEquals("line-50", lines.get(0));
            assertEquals("line-249", lines.get(199));
        } finally {
            transport.close();
        }
    }
}
