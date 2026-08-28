package com.mindcli.platform.render.terminal;

/**
 * ANSI 16 色降级代码，仅保存前景 / 背景 SGR code，不引入 JLine 依赖。
 */
public enum Ansi16 {
    BLACK(30, 40),
    RED(31, 41),
    MAGENTA(35, 45),
    WHITE(37, 47),
    BRIGHT_BLACK(90, 100),
    BRIGHT_WHITE(97, 107);

    private final int foreground;
    private final int background;

    Ansi16(int foreground, int background) {
        this.foreground = foreground;
        this.background = background;
    }

    public int foreground() {
        return foreground;
    }

    public int background() {
        return background;
    }
}
