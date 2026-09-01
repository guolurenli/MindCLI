package com.mindcli.platform.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConfigValueResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesPropertyBeforeEnvironmentAndDotEnv() throws Exception {
        Path project = Files.createDirectory(tempDir.resolve("project"));
        Path home = Files.createDirectory(tempDir.resolve("home"));
        Files.writeString(project.resolve(".env"), "TOKEN=project\n");
        Files.writeString(home.resolve(".env"), "TOKEN=home\n");
        ConfigValueResolver resolver = new ConfigValueResolver(
                project, home,
                Map.of("mindcli.token", "property")::get,
                Map.of("TOKEN", "environment")::get);

        assertEquals("property", resolver.resolve("mindcli.token", "TOKEN", "default"));
    }

    @Test
    void resolvesEnvironmentBeforeProjectAndUserDotEnv() throws Exception {
        Path project = Files.createDirectory(tempDir.resolve("project"));
        Path home = Files.createDirectory(tempDir.resolve("home"));
        Files.writeString(project.resolve(".env"), "TOKEN=project\n");
        Files.writeString(home.resolve(".env"), "TOKEN=home\n");
        ConfigValueResolver resolver = new ConfigValueResolver(
                project, home, key -> null, Map.of("TOKEN", "environment")::get);

        assertEquals("environment", resolver.resolve("mindcli.token", "TOKEN", "default"));
    }

    @Test
    void projectDotEnvWinsAndOptionalQuotesAreRemoved() throws Exception {
        Path project = Files.createDirectory(tempDir.resolve("project"));
        Path home = Files.createDirectory(tempDir.resolve("home"));
        Files.writeString(project.resolve(".env"), "# comment\nTOKEN=\"project value\"\n");
        Files.writeString(home.resolve(".env"), "TOKEN='home value'\n");
        ConfigValueResolver resolver = new ConfigValueResolver(project, home, key -> null, key -> null);

        assertEquals("project value", resolver.resolve("mindcli.token", "TOKEN", "default"));
    }

    @Test
    void userDotEnvAndDefaultCoverMissingProjectValues() throws Exception {
        Path project = Files.createDirectory(tempDir.resolve("project"));
        Path home = Files.createDirectory(tempDir.resolve("home"));
        Files.writeString(project.resolve(".env"), "MALFORMED\nEMPTY=   \n");
        Files.writeString(home.resolve(".env"), "TOKEN=home\n");
        ConfigValueResolver resolver = new ConfigValueResolver(project, home, key -> null, key -> null);

        assertEquals("home", resolver.resolve("mindcli.token", "TOKEN", "default"));
        assertEquals("default", resolver.resolve("mindcli.other", "OTHER", "default"));
        assertNull(resolver.resolve("mindcli.other", "OTHER", null));
    }

}
