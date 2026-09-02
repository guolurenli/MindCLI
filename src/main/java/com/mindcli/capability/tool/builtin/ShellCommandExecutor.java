package com.mindcli.capability.tool.builtin;

import com.mindcli.platform.security.CommandGuard;
import com.mindcli.platform.security.PolicyException;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Executes a short command in a bounded workspace. */
public final class ShellCommandExecutor {
    private static final int MAX_OUTPUT_CHARS = 8_000;

    private ShellCommandExecutor() {
    }

    public static String execute(String command, String projectPath, long timeoutSeconds) {
        String normalized = command == null ? "" : command.trim();
        if (normalized.isEmpty()) {
            return "执行命令失败: 命令不能为空";
        }
        String denyReason = CommandGuard.check(normalized);
        if (denyReason != null) {
            throw new PolicyException(denyReason);
        }

        ExecutorService outputReaderExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "mindcli-command-output");
            thread.setDaemon(true);
            return thread;
        });
        Process process = null;
        try {
            ProcessBuilder pb = commandProcessBuilder(normalized);
            pb.directory(new File(projectPath));
            pb.redirectErrorStream(true);
            process = pb.start();
            Process runningProcess = process;
            Future<String> outputFuture = outputReaderExecutor.submit(
                    () -> readProcessOutput(runningProcess));

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                outputFuture.cancel(true);
                return "命令执行超时（" + timeoutSeconds + "秒），已强制终止";
            }
            String output = getCommandOutput(outputFuture);
            return String.format("命令执行完成 (exit code: %d, cwd: %s)\n%s",
                    process.exitValue(), projectPath, output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return "用户取消了此次工具调用";
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            return "执行命令失败: " + e.getMessage();
        } finally {
            outputReaderExecutor.shutdownNow();
        }
    }

    private static ProcessBuilder commandProcessBuilder(String command) {
        if (isWindows()) {
            String script = "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; "
                    + "$OutputEncoding=[System.Text.Encoding]::UTF8; "
                    + command;
            return new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-Command", script);
        }
        return new ProcessBuilder("bash", "-c", command);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String readProcessOutput(Process process) throws Exception {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() < MAX_OUTPUT_CHARS) {
                    int remaining = MAX_OUTPUT_CHARS - output.length();
                    output.append(line, 0, Math.min(line.length(), remaining)).append('\n');
                }
            }
        }
        if (output.length() >= MAX_OUTPUT_CHARS) {
            return output.substring(0, MAX_OUTPUT_CHARS) + "\n...(输出已截断)";
        }
        return output.toString();
    }

    private static String getCommandOutput(Future<String> outputFuture) throws Exception {
        try {
            return outputFuture.get(2, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            outputFuture.cancel(true);
            return "(命令已结束，但输出读取超时)";
        }
    }
}
