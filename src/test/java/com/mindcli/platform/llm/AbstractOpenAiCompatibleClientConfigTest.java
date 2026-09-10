package com.mindcli.platform.llm;

import com.mindcli.platform.config.ConfigValueResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AbstractOpenAiCompatibleClientConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesTimeoutFromProjectDotEnvWhenHigherPrecedenceValuesAreMissing() throws Exception {
        Path project = Files.createDirectory(tempDir.resolve("project"));
        Path home = Files.createDirectory(tempDir.resolve("home"));
        Files.writeString(project.resolve(".env"), "MINDCLI_LLM_READ_TIMEOUT_SECONDS=17\n");
        ConfigValueResolver resolver = new ConfigValueResolver(
                project, home);

        assertEquals(17L, AbstractOpenAiCompatibleClient.readTimeoutSeconds(
                resolver,
                "mindcli.llm.read.timeout.seconds",
                "MINDCLI_LLM_READ_TIMEOUT_SECONDS",
                300));
    }

    @Test
    void invalidDotEnvValuesUseDefault() throws Exception {
        Path project = Files.createDirectory(tempDir.resolve("project"));
        Path home = Files.createDirectory(tempDir.resolve("home"));
        Files.writeString(project.resolve(".env"), "MINDCLI_LLM_READ_TIMEOUT_SECONDS=0\n");
        ConfigValueResolver invalidResolver = new ConfigValueResolver(project, home);
        assertEquals(300L, AbstractOpenAiCompatibleClient.readTimeoutSeconds(
                invalidResolver,
                "mindcli.llm.read.timeout.seconds",
                "MINDCLI_LLM_READ_TIMEOUT_SECONDS",
                300));
    }
}
