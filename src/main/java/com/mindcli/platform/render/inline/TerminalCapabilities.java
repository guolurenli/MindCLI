package com.mindcli.platform.render.inline;

import org.jline.terminal.Size;
import org.jline.terminal.Terminal;

import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;

/**
 * 终端能力探测：决定 inline 渲染器的各项特性是否可启用。
 *
 * <p>探测逻辑保守——能开则开，老终端 / 非 TTY 环境优雅降级。
 */
public final class TerminalCapabilities {

    private TerminalCapabilities() {
    }

    /** 终端是否能渲染 ANSI 转义序列（颜色、光标控制、inline status 等）。 */
    public static boolean supportsAnsi(Terminal terminal) {
        if (terminal == null) {
            return false;
        }
        String type = terminal.getType();
        if (type != null && type.equalsIgnoreCase("dumb")) {
            return false;
        }
        if (System.getenv("NO_COLOR") != null) {
            // NO_COLOR 只影响样式，不影响光标控制——保留 true，颜色由 AnsiStyle 自己关
            return true;
        }
        String envTerm = System.getenv("TERM");
        return envTerm == null || !envTerm.equalsIgnoreCase("dumb");
    }

    /**
     * 终端是否适合启用 inline status 状态区。
     * 同时校验终端尺寸合理（rows ≥ 5）。
     */
    public static boolean supportsScrollRegion(Terminal terminal) {
        if (!supportsAnsi(terminal)) {
            return false;
        }
        if (Boolean.parseBoolean(System.getenv("MINDCLI_NO_STATUSBAR"))) {
            return false;
        }
        if (Boolean.parseBoolean(System.getProperty("mindcli.no.statusbar"))) {
            return false;
        }
        Size size = safeSize(terminal);
        return size.getRows() >= 5 && size.getColumns() >= 20;
    }

    /** 终端是否支持 24-bit TrueColor（用于丰富的代码高亮等）。 */
    public static boolean supportsTrueColor() {
        String colorterm = System.getenv("COLORTERM");
        return "truecolor".equalsIgnoreCase(colorterm) || "24bit".equalsIgnoreCase(colorterm);
    }

    public static Size safeSize(Terminal terminal) {
        try {
            Size s = terminal.getSize();
            if (s == null || s.getRows() <= 0 || s.getColumns() <= 0) {
                return new Size(80, 24);
            }
            return s;
        } catch (Exception e) {
            return new Size(80, 24);
        }
    }

    public static boolean supportsUnicodeGlyphs(Terminal terminal, String glyphs) {
        if (terminal == null || glyphs == null || glyphs.isEmpty()) {
            return false;
        }
        Charset charset = safeOutputEncoding(terminal);
        if (charset == null) {
            return true;
        }
        CharsetEncoder encoder = charset.newEncoder();
        return encoder.canEncode(glyphs);
    }

    private static Charset safeOutputEncoding(Terminal terminal) {
        try {
            Charset output = terminal.outputEncoding();
            if (output != null) {
                return output;
            }
        } catch (RuntimeException ignored) {
            // Fall through to the older aggregate encoding.
        }
        try {
            return terminal.encoding();
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
