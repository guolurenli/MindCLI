package com.mindcli.platform.render.terminal;

/**
 * 语义颜色角色：调用点只消费角色，不直接选择基础色 token。
 *
 * <p>后续调整图片取色只改 {@link NekoPalette}，无需逐个审查调用点。
 */
public enum UiColorRole {
    PRIMARY(NekoPalette.CREAM),
    ACCENT(NekoPalette.ROSE),
    SECONDARY(NekoPalette.BLUSH),
    MUTED(NekoPalette.TAUPE),
    DANGER(NekoPalette.ROUGE),
    PANEL_BG(NekoPalette.PANEL);

    private final NekoPalette palette;

    UiColorRole(NekoPalette palette) {
        this.palette = palette;
    }

    public NekoPalette palette() {
        return palette;
    }
}
