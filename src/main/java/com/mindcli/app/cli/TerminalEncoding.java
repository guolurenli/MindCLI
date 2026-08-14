package com.mindcli.app.cli;

import org.jline.terminal.TerminalBuilder;

import java.io.Console;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

final class TerminalEncoding {

    static final String PROPERTY = "mindcli.terminal.encoding";
    static final String ENV = "MINDCLI_TERMINAL_ENCODING";

    private TerminalEncoding() {
    }

    record Plan(Charset charset, String source, String startupNote) {
        boolean isUtf8Compatible() {
            return StandardCharsets.UTF_8.equals(charset);
        }
    }

    static Plan detect() {
        String dotenvEncoding = CliBootstrap.loadConfigValue(ENV, "");
        return detect(System.getProperties(),
                System.getenv(),
                dotenvEncoding,
                consoleCharset(),
                Charset.defaultCharset(),
                System.getProperty("os.name", ""));
    }

    static Plan detect(Properties properties,
                       Map<String, String> env,
                       Charset consoleCharset,
                       Charset defaultCharset,
                       String osName) {
        return detect(properties, env, "", consoleCharset, defaultCharset, osName);
    }

    static Plan detect(Properties properties,
                       Map<String, String> env,
                       String dotenvEncoding,
                       Charset consoleCharset,
                       Charset defaultCharset,
                       String osName) {
        List<String> notes = new ArrayList<>();
        Charset charset = null;
        String source = "";

        Resolution property = resolveConfigured(properties == null ? null : properties.getProperty(PROPERTY), PROPERTY);
        if (property.invalidNote() != null) {
            notes.add(property.invalidNote());
        }
        if (property.charset() != null) {
            charset = property.charset();
            source = property.source();
        }

        if (charset == null) {
            Resolution environment = resolveConfigured(env == null ? null : env.get(ENV), ENV);
            if (environment.invalidNote() != null) {
                notes.add(environment.invalidNote());
            }
            if (environment.charset() != null) {
                charset = environment.charset();
                source = environment.source();
            }
        }

        if (charset == null) {
            Resolution dotenv = resolveConfigured(dotenvEncoding, ".env " + ENV);
            if (dotenv.invalidNote() != null) {
                notes.add(dotenv.invalidNote());
            }
            if (dotenv.charset() != null) {
                charset = dotenv.charset();
                source = dotenv.source();
            }
        }

        if (charset == null) {
            Resolution jvmStream = resolveJvmStreamEncoding(properties);
            if (jvmStream.invalidNote() != null) {
                notes.add(jvmStream.invalidNote());
            }
            if (jvmStream.charset() != null) {
                charset = jvmStream.charset();
                source = jvmStream.source();
            }
        }

        if (charset == null && consoleCharset != null) {
            charset = consoleCharset;
            source = "console";
        }
        if (charset == null) {
            charset = defaultCharset == null ? StandardCharsets.UTF_8 : defaultCharset;
            source = "defaultCharset";
        }

        if (!StandardCharsets.UTF_8.equals(charset)) {
            notes.add(nonUtf8Note(charset, source, osName));
        }

        return new Plan(charset, source, String.join("\n", notes));
    }

    private static Resolution resolveJvmStreamEncoding(Properties properties) {
        if (properties == null) {
            return Resolution.empty();
        }
        String[] keys = {"sun.stdout.encoding", "sun.stderr.encoding", "sun.stdin.encoding"};
        List<String> notes = new ArrayList<>();
        for (String key : keys) {
            Resolution resolution = resolveConfigured(properties.getProperty(key), key);
            if (resolution.invalidNote() != null) {
                notes.add(resolution.invalidNote());
                continue;
            }
            if (resolution.charset() != null) {
                return resolution;
            }
        }
        return notes.isEmpty() ? Resolution.empty() : new Resolution(null, "", String.join("\n", notes));
    }

    static TerminalBuilder applyTo(TerminalBuilder builder, Plan plan) {
        if (builder == null || plan == null || plan.charset() == null) {
            return builder;
        }
        return builder.encoding(plan.charset())
                .stdinEncoding(plan.charset())
                .stdoutEncoding(plan.charset())
                .stderrEncoding(plan.charset());
    }

    static void configureStandardStreams(Plan plan) {
        if (plan == null || plan.charset() == null || Boolean.getBoolean("mindcli.terminal.keepStandardStreams")) {
            return;
        }
        try {
            System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, plan.charset()));
            System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, plan.charset()));
        } catch (RuntimeException ignored) {
            // Keep JVM-provided streams if the host forbids wrapping file descriptors.
        }
    }

    private static Resolution resolveConfigured(String raw, String source) {
        if (raw == null || raw.isBlank()) {
            return Resolution.empty();
        }
        String value = raw.trim();
        try {
            return new Resolution(Charset.forName(value), source, null);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            return new Resolution(null, source,
                    "ENCODING: 未识别 " + source + "=" + value + "，已回退到自动探测编码。");
        }
    }

    private static String nonUtf8Note(Charset charset, String source, String osName) {
        String base = "ENCODING: 当前终端编码 " + charset.displayName()
                + "（来源: " + source + "），MindCLI 已按该编码读写；"
                + "Unicode 图标和猫耳助手会自动降级。";
        if (isWindows(osName)) {
            return base + " 若中文仍乱码，建议使用 Windows Terminal/PowerShell 执行 chcp 65001，"
                    + "或设置 " + ENV + "=" + charset.name() + "。";
        }
        return base + " 若文本仍乱码，请设置 " + ENV + "=<charset> 与终端编码一致。";
    }

    private static boolean isWindows(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("win");
    }

    private static Charset consoleCharset() {
        try {
            Console console = System.console();
            return console == null ? null : console.charset();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private record Resolution(Charset charset, String source, String invalidNote) {
        static Resolution empty() {
            return new Resolution(null, "", null);
        }
    }
}
