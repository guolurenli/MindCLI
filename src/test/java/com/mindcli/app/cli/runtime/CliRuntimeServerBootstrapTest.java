package com.mindcli.app.cli.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliRuntimeServerBootstrapTest {

    @Test
    void recognizesHttpServeCommand() {
        assertTrue(CliRuntimeServerBootstrap.isRuntimeServeCommand(new String[]{"serve", "--http"}));
        assertTrue(CliRuntimeServerBootstrap.isRuntimeServeCommand(new String[]{"SERVE", "--HTTP", "--port", "9090"}));
        assertFalse(CliRuntimeServerBootstrap.isRuntimeServeCommand(new String[]{"serve"}));
        assertFalse(CliRuntimeServerBootstrap.isRuntimeServeCommand(new String[]{"run", "--http"}));
    }

    @Test
    void parsesPortAndFallsBackForInvalidInput() {
        assertEquals(9090, CliRuntimeServerBootstrap.parseServePort(
                new String[]{"serve", "--http", "--port", "9090"}, 8080));
        assertEquals(8080, CliRuntimeServerBootstrap.parseServePort(
                new String[]{"serve", "--http", "--port", "bad"}, 8080));
        assertEquals(8080, CliRuntimeServerBootstrap.parseServePort(
                new String[]{"serve", "--http"}, 8080));
    }
}
