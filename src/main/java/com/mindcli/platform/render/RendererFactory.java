package com.mindcli.platform.render;

import com.mindcli.platform.render.inline.InlineRenderer;
import com.mindcli.platform.render.inline.TerminalCapabilities;
import org.jline.terminal.Terminal;

/**
 * 启动时根据环境变量选择渲染器形态。
 *
 * <p>选型规则：
 * <ul>
 *   <li>{@code -Dmindcli.renderer} > {@code MINDCLI_RENDERER} 环境变量 > 默认 inline</li>
 *   <li>{@code inline}（默认）→ Inline 流式渲染</li>
 *   <li>{@code plain} → {@link PlainRenderer}</li>
 * </ul>
 *
 * <p>当 inline 目标渲染器初始化失败（如终端不支持 ANSI），自动 fallback 到
 * {@link PlainRenderer}，并在 stderr 打日志。
 */
public final class RendererFactory {

    public enum Mode {
        INLINE, PLAIN
    }

    private RendererFactory() {
    }

    public static Mode resolveMode() {
        String prop = System.getProperty("mindcli.renderer");
        if (prop != null && !prop.isBlank()) {
            return parse(prop);
        }
        String env = System.getenv("MINDCLI_RENDERER");
        if (env != null && !env.isBlank()) {
            return parse(env);
        }
        return Mode.INLINE;
    }

    private static Mode parse(String raw) {
        return switch (raw.trim().toLowerCase()) {
            case "lanterna", "tui" -> {
                System.err.println("⚠️ Lanterna/TUI 已移除，回退到 inline");
                yield Mode.INLINE;
            }
            case "plain" -> Mode.PLAIN;
            case "inline" -> Mode.INLINE;
            default -> {
                System.err.println("⚠️ 未识别的 MINDCLI_RENDERER='" + raw + "'，回退到 inline");
                yield Mode.INLINE;
            }
        };
    }

    /**
     * 创建 CLI 循环内使用的渲染器。inline 模式如果终端不支持 ANSI（如 dumb 终端），
     * 回退到 plain。
     *
     * @param terminal JLine terminal，用于 inline 模式探测能力。可为 null。
     */
    public static Renderer create(Mode mode, Terminal terminal) {
        return switch (mode) {
            case PLAIN -> new PlainRenderer();
            case INLINE -> {
                if (TerminalCapabilities.supportsAnsi(terminal)) {
                    yield new InlineRenderer(terminal);
                }
                System.err.println("⚠️ 终端不支持 ANSI，inline 模式回退到 plain");
                yield new PlainRenderer();
            }
        };
    }
}
