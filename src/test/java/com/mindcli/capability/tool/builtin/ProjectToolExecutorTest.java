package com.mindcli.capability.tool.builtin;

import com.mindcli.platform.security.PathGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectToolExecutorTest {

    @TempDir
    Path workspace;

    @Test
    void createsJavaProjectInsideWorkspace() {
        ProjectToolExecutor executor = new ProjectToolExecutor(new PathGuard(workspace.toString()));

        String result = executor.create(Map.of("name", "demo", "type", "java"));

        assertTrue(result.contains("项目已创建: demo"));
        assertTrue(Files.isDirectory(workspace.resolve("demo/src/main/java")));
        assertTrue(Files.isDirectory(workspace.resolve("demo/src/main/resources")));
        assertTrue(Files.isRegularFile(workspace.resolve("demo/pom.xml")));
    }

    @Test
    void createsPythonAndNodeEntryFiles() {
        ProjectToolExecutor executor = new ProjectToolExecutor(new PathGuard(workspace.toString()));

        executor.create(Map.of("name", "py-demo", "type", "python"));
        executor.create(Map.of("name", "node-demo", "type", "node"));

        assertTrue(Files.isDirectory(workspace.resolve("py-demo/py-demo")));
        assertTrue(Files.isRegularFile(workspace.resolve("py-demo/main.py")));
        assertTrue(Files.isRegularFile(workspace.resolve("py-demo/requirements.txt")));
        assertTrue(Files.isRegularFile(workspace.resolve("node-demo/package.json")));
    }
}
