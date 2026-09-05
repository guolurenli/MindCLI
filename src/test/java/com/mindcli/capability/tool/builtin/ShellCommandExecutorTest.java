package com.mindcli.capability.tool.builtin;

import com.mindcli.platform.security.PolicyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellCommandExecutorTest {

    @Test
    void executesInWorkspaceAndPreservesOutputContract(@TempDir Path workspace) {
        String result = ShellCommandExecutor.execute("pwd", workspace.toString(), 10);

        assertTrue(result.contains(workspace.toString()), result);
    }

    @Test
    void rejectsCommandsBeforeStartingProcess() {
        assertThrows(PolicyException.class,
                () -> ShellCommandExecutor.execute("find / -name pom.xml", ".", 10));
    }
}
