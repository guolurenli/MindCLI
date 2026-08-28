package com.mindcli.platform.render.terminal;

/**
 * 「猫耳助手」暖色基础色 token，纯数据，不拼 ANSI 转义、不 import JLine。
 *
 * <p>RGB 取自启动图 {@code src/main/resources/ui/noke*.png} 的共同视觉风格，
 * {@link #ansi16()} 提供非真彩终端下的 16 色降级。
 */
public enum NekoPalette {
    INK(0x0A, 0x0D, 0x15, Ansi16.BLACK),
    PANEL(0x1F, 0x1C, 0x22, Ansi16.BRIGHT_BLACK),
    CREAM(0xF4, 0xED, 0xE8, Ansi16.BRIGHT_WHITE),
    BLUSH(0xEB, 0xD7, 0xD1, Ansi16.WHITE),
    ROSE(0xD9, 0xA9, 0xA9, Ansi16.MAGENTA),
    ROUGE(0xE0, 0x5F, 0x6F, Ansi16.RED),
    TAUPE(0x8C, 0x81, 0x7F, Ansi16.BRIGHT_BLACK);

    private final int r;
    private final int g;
    private final int b;
    private final Ansi16 ansi16;

    NekoPalette(int r, int g, int b, Ansi16 ansi16) {
        this.r = r;
        this.g = g;
        this.b = b;
        this.ansi16 = ansi16;
    }

    public int r() {
        return r;
    }

    public int g() {
        return g;
    }

    public int b() {
        return b;
    }

    public Ansi16 ansi16() {
        return ansi16;
    }
}
