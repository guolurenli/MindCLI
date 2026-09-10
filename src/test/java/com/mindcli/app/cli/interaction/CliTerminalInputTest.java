package com.mindcli.app.cli.interaction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CliTerminalInputTest {

    @Test
    void escapeCancellationIsSafeWithoutTerminal() {
        assertFalse(CliTerminalInput.readEscCancel(null));
    }

    @Test
    void emptyBurstIsReturnedForNullTerminal() throws Exception {
        assertEquals("", CliTerminalInput.readInputBurst(null, 1, 1, 1));
    }
}
