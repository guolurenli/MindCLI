package com.mindcli.app.cli.interaction;

import com.mindcli.capability.image.ClipboardImage;
import com.mindcli.platform.render.inline.InlineRenderer;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.widget.AutosuggestionWidgets;
import org.jline.widget.AutopairWidgets;
import org.jline.keymap.KeyMap;

/** JLine widget registration kept separate from CLI startup and command dispatch. */
public final class CliInteractiveWidgets {
    private CliInteractiveWidgets() {
    }

    public static void configureJLineInteractiveWidgets(LineReader lineReader) {
        if (lineReader == null) {
            return;
        }
        new AutosuggestionWidgets(lineReader).enable();
        new AutopairWidgets(lineReader).enable();
    }

    public static void configureSlashCommandHint(LineReader lineReader) {
        if (lineReader == null) {
            return;
        }
        lineReader.getWidgets().put("mindcli-slash-command-hint", () -> {
            lineReader.getBuffer().write("/");
            return true;
        });
        Reference slashHint = new Reference("mindcli-slash-command-hint");
        for (String mapName : new String[]{LineReader.MAIN, LineReader.EMACS, LineReader.VIINS}) {
            KeyMap<org.jline.reader.Binding> map = lineReader.getKeyMaps().get(mapName);
            if (map != null) {
                map.bind(slashHint, "/");
            }
        }
    }

    public static void bindCtrlOToFoldableBlocks(LineReader lineReader, InlineRenderer inline) {
        if (lineReader == null || inline == null) {
            return;
        }
        lineReader.getWidgets().put("mindcli-toggle-foldable", () -> {
            inline.toggleLastBlock();
            lineReader.callWidget(LineReader.REDISPLAY);
            return true;
        });
        bindToCommonKeyMaps(lineReader, new Reference("mindcli-toggle-foldable"),
                String.valueOf((char) 15));
    }

    public static void bindCtrlVToClipboardImage(LineReader lineReader) {
        if (lineReader == null) {
            return;
        }
        lineReader.getWidgets().put("mindcli-paste-clipboard-image", () -> {
            ClipboardImage.GrabResult grab = ClipboardImage.grab();
            if (!grab.ok()) {
                lineReader.printAbove("⚠️ Ctrl+V 抓图失败: " + grab.error());
                lineReader.callWidget(LineReader.REDISPLAY);
                return true;
            }
            String token = "@image:<" + grab.path().toAbsolutePath() + "> ";
            lineReader.getBuffer().write(token);
            lineReader.callWidget(LineReader.REDISPLAY);
            return true;
        });
        bindToCommonKeyMaps(lineReader, new Reference("mindcli-paste-clipboard-image"),
                String.valueOf((char) 22));
    }

    public static void bindEscToClearInput(LineReader lineReader) {
        if (lineReader == null) {
            return;
        }
        lineReader.getWidgets().put("mindcli-clear-input", () -> {
            clearInputBuffer(lineReader);
            lineReader.callWidget(LineReader.REDISPLAY);
            return true;
        });
        bindToCommonKeyMaps(lineReader, new Reference("mindcli-clear-input"), KeyMap.esc());
    }

    public static void clearInputBuffer(LineReader lineReader) {
        if (lineReader == null || lineReader.getBuffer() == null) {
            return;
        }
        lineReader.getBuffer().clear();
    }

    private static void bindToCommonKeyMaps(LineReader lineReader, Reference reference, String key) {
        for (String mapName : new String[]{LineReader.MAIN, LineReader.EMACS, LineReader.VIINS}) {
            KeyMap<org.jline.reader.Binding> map = lineReader.getKeyMaps().get(mapName);
            if (map != null) {
                map.bind(reference, key);
            }
        }
    }
}
