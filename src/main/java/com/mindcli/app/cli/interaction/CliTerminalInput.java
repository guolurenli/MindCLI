package com.mindcli.app.cli.interaction;

import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;

import java.io.IOException;

/** Low-level raw terminal reads used by the interactive CLI. */
public final class CliTerminalInput {
    private CliTerminalInput() {
    }

    public static boolean readEscCancel(Terminal terminal) {
        if (terminal == null) {
            return false;
        }
        try {
            NonBlockingReader reader = terminal.reader();
            int next = reader.read(50);
            if (next == NonBlockingReader.READ_EXPIRED || next < 0) {
                return false;
            }
            String escTail = next == 27 ? readInputBurst(terminal, 80, 20, 120) : null;
            if (next != 27) {
                while (true) {
                    int more = reader.read(1);
                    if (more == NonBlockingReader.READ_EXPIRED || more < 0) {
                        break;
                    }
                }
            }
            return next == 27
                    && CliInputSupport.classifyEscapeSequence(escTail)
                    == CliInputSupport.EscapeSequenceType.STANDALONE_ESC;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static String readInputBurst(Terminal terminal, long firstWaitMs,
                                        long idleWaitMs, long maxWaitMs)
            throws IOException, InterruptedException {
        if (terminal == null) {
            return "";
        }
        NonBlockingReader reader = terminal.reader();
        StringBuilder buffer = new StringBuilder();
        long start = System.currentTimeMillis();
        long waitMs = firstWaitMs;
        while (System.currentTimeMillis() - start < maxWaitMs) {
            int next = reader.read(waitMs);
            if (next == NonBlockingReader.READ_EXPIRED || next < 0) {
                break;
            }
            buffer.append((char) next);
            waitMs = idleWaitMs;
        }
        return buffer.toString();
    }
}
