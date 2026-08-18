package com.mindcli.platform.render.terminal;

/**
 * 终端 ANSI 样式辅助。
 */
public final class AnsiStyle {
    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String DIM = "\u001B[2m";
    private static final String ITALIC = "\u001B[3m";
    private AnsiStyle() {
    }

    public static String heading(String text) {
        return wrap(BOLD + fg(UiColorRole.PRIMARY), text);
    }

    public static String section(String text) {
        return wrap(BOLD + fg(UiColorRole.ACCENT), text);
    }

    public static String answerMarker() {
        return wrap(BOLD + fg(UiColorRole.ACCENT), "▪");
    }

    public static String subtle(String text) {
        return wrap(DIM + fg(UiColorRole.MUTED), text);
    }

    public static String thinking(String text) {
        return wrap(ITALIC + fg(UiColorRole.MUTED), text);
    }

    public static String userMessageBlock(String text, int columns) {
        String safe = text == null ? "" : text.strip();
        int width = Math.max(20, columns);
        String[] lines = safe.isEmpty() ? new String[]{""} : safe.split("\\R", -1);
        StringBuilder rendered = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                rendered.append('\n');
            }
            rendered.append(userMessageBlockLine(lines[i], width));
        }
        return rendered.toString();
    }

    private static String userMessageBlockLine(String text, int width) {
        String safe = text == null ? "" : text;
        String prefix = "> ";
        String content = prefix + safe;
        int padding = Math.max(0, width - displayWidth(content));
        String line = content + " ".repeat(padding);
        if (!isEnabled()) {
            return line.stripTrailing();
        }
        String panelBg = bg(UiColorRole.PANEL_BG);
        String prefixColor = fg(UiColorRole.ACCENT);
        return panelBg + prefixColor + prefix + RESET + panelBg + safe + " ".repeat(padding) + RESET;
    }

    public static String codeLabel(String text) {
        return wrap(BOLD + fg(UiColorRole.SECONDARY), text);
    }

    public static String error(String text) {
        return wrap(BOLD + fg(UiColorRole.DANGER), text);
    }

    public static String quotePrefix(String text) {
        return wrap(DIM + fg(UiColorRole.MUTED), text);
    }

    public static String emphasis(String text) {
        return wrap(BOLD, text);
    }

    public static boolean isEnabled() {
        return determineEnabled();
    }

    /**
     * 是否确认真彩：显式开关优先，其次 {@code COLORTERM=truecolor|24bit}。
     */
    public static boolean supportsTrueColor() {
        String property = System.getProperty("mindcli.render.truecolor");
        if (property != null && !property.isBlank()) {
            return Boolean.parseBoolean(property);
        }
        String env = System.getenv("MINDCLI_TRUECOLOR");
        if (env != null && !env.isBlank()) {
            return Boolean.parseBoolean(env);
        }
        String colorterm = System.getenv("COLORTERM");
        return colorterm != null
                && (colorterm.equalsIgnoreCase("truecolor") || colorterm.equalsIgnoreCase("24bit"));
    }

    /** 按终端能力返回角色的前景色转义；颜色关闭或 role 为空时返回空串。 */
    public static String fg(UiColorRole role) {
        if (!isEnabled() || role == null) {
            return "";
        }
        NekoPalette palette = role.palette();
        if (supportsTrueColor()) {
            return "\u001B[38;2;" + palette.r() + ";" + palette.g() + ";" + palette.b() + "m";
        }
        return "\u001B[" + palette.ansi16().foreground() + "m";
    }

    /** 按终端能力返回角色的背景色转义；颜色关闭或 role 为空时返回空串。 */
    public static String bg(UiColorRole role) {
        if (!isEnabled() || role == null) {
            return "";
        }
        NekoPalette palette = role.palette();
        if (supportsTrueColor()) {
            return "\u001B[48;2;" + palette.r() + ";" + palette.g() + ";" + palette.b() + "m";
        }
        return "\u001B[" + palette.ansi16().background() + "m";
    }

    private static String wrap(String prefix, String text) {
        if (!isEnabled() || text == null || text.isEmpty()) {
            return text;
        }
        return prefix + text + RESET;
    }

    private static int displayWidth(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
            width += block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                    || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                    || block == Character.UnicodeBlock.HIRAGANA
                    || block == Character.UnicodeBlock.KATAKANA
                    || block == Character.UnicodeBlock.EMOTICONS
                    || block == Character.UnicodeBlock.MISCELLANEOUS_SYMBOLS_AND_PICTOGRAPHS
                    || block == Character.UnicodeBlock.TRANSPORT_AND_MAP_SYMBOLS ? 2 : 1;
            i += Character.charCount(codePoint);
        }
        return width;
    }

    private static boolean determineEnabled() {
        String property = System.getProperty("mindcli.render.color");
        if (property != null && !property.isBlank()) {
            return Boolean.parseBoolean(property);
        }

        if (System.getenv("NO_COLOR") != null) {
            return false;
        }

        String term = System.getenv("TERM");
        return term == null || !term.equalsIgnoreCase("dumb");
    }
}
