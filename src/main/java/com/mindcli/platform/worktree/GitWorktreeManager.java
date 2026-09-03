package com.mindcli.platform.worktree;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Git worktree 隔离管理器（基于命令行 git）。
 *
 * 为 team 模式的并行写入步骤提供隔离工作目录：每个写入 step 在独立 worktree 里写文件、
 * 提交，最后合并回主仓库。合并冲突时不静默覆盖，而是撤销合并并把冲突文件清单上报。
 *
 * 注意：JGit 7.x 尚无 worktree add/remove API，因此这里走系统 git 命令；git 不可用或
 * 项目不是 git 仓库时，调用方应回退串行执行（见 {@link #isGitRepository(Path)}）。
 */
public class GitWorktreeManager {
    private static final Logger log = LoggerFactory.getLogger(GitWorktreeManager.class);
    private static final long GIT_TIMEOUT_SECONDS = 120;

    public GitWorktreeManager() {
    }

    /** 探测项目根是否是一个真实 git 仓库。 */
    public boolean isGitRepository(Path root) {
        if (root == null) {
            return false;
        }
        CmdResult result = runGit(root, "rev-parse", "--is-inside-work-tree");
        return result.exitCode() == 0 && result.stdout().trim().equals("true");
    }

    /** 检查 git 命令是否可用（找不到可执行文件时返回 false）。 */
    public boolean isGitAvailable() {
        CmdResult result = runGit(null, "--version");
        return result.exitCode() == 0;
    }

    /**
     * 把主工作区所有未提交变更提交为一个 checkpoint（若工作区干净则跳过）。
     * worktree 只能基于 commit 创建，这是隔离并行写入前必须固化的基线。
     *
     * @return 是否产生了新的 checkpoint commit
     */
    public synchronized boolean commitCheckpoint(Path root, String message) throws IOException {
        CmdResult add = runGit(root, "add", "-A");
        if (add.exitCode() != 0) {
            throw new IOException("git add 失败: " + add.stderr());
        }
        CmdResult status = runGit(root, "status", "--porcelain");
        if (status.exitCode() != 0) {
            throw new IOException("git status 失败: " + status.stderr());
        }
        if (status.stdout().isBlank()) {
            return false;
        }
        CmdResult commit = runGit(root, "commit", "-m",
                message == null || message.isBlank()
                        ? "mindcli: checkpoint before parallel team writes"
                        : message);
        if (commit.exitCode() != 0) {
            throw new IOException("git commit 失败: " + commit.stderr());
        }
        return true;
    }

    /** 基于当前 HEAD 创建一个新 worktree 并新建同名分支。 */
    public synchronized WorktreeHandle create(Path root, Path worktreePath, String branchName) throws IOException {
        CmdResult result = runGit(root, "worktree", "add", "-b", branchName, worktreePath.toString());
        if (result.exitCode() != 0) {
            throw new IOException("git worktree add 失败: " + result.stderr());
        }
        return new WorktreeHandle(worktreePath, branchName);
    }

    /**
     * 提交 worktree 内的变更，合并回主仓库，并清理 worktree。
     *
     * 无变更时返回 {@link WorktreeMergeResult#nothing()}；合并冲突时撤销合并（git merge --abort）
     * 并返回 {@link WorktreeMergeResult#conflicting(List)}，主工作区保持合并前状态。
     */
    public synchronized WorktreeMergeResult mergeAndDispose(Path root, WorktreeHandle handle, String commitMessage)
            throws IOException {
        if (handle == null) {
            return WorktreeMergeResult.nothing();
        }
        String message = commitMessage == null || commitMessage.isBlank()
                ? "mindcli: worktree " + handle.branchName()
                : commitMessage;
        if (!commitWorktree(handle, message)) {
            dispose(root, handle);
            return WorktreeMergeResult.nothing();
        }

        CmdResult merge = runGit(root, "merge", handle.branchName());
        if (merge.exitCode() != 0) {
            List<String> conflicts = conflictingFiles(root);
            runGit(root, "merge", "--abort");
            log.warn("Worktree merge conflict for branch {}: {}", handle.branchName(), conflicts);
            dispose(root, handle);
            return WorktreeMergeResult.conflicting(conflicts);
        }
        dispose(root, handle);
        return WorktreeMergeResult.clean();
    }

    /**
     * 在临时 integration worktree 中合并一批 step 分支，全部成功后再更新主工作区。
     *
     * <p>这样可以避免第一个 step 已经合并到主工作区、第二个 step 冲突时留下部分集成结果。</p>
     */
    public synchronized BatchMergeResult mergeBatchAndDispose(Path root, List<WorktreeHandle> handles,
                                                               String commitMessagePrefix) throws IOException {
        if (root == null || handles == null || handles.isEmpty()) {
            return BatchMergeResult.nothing();
        }

        List<WorktreeHandle> validHandles = handles.stream()
                .filter(handle -> handle != null)
                .toList();
        List<WorktreeHandle> changedHandles = new ArrayList<>();
        WorktreeHandle integration = null;
        try {
            for (WorktreeHandle handle : validHandles) {
                String message = commitMessagePrefix == null || commitMessagePrefix.isBlank()
                        ? "mindcli: worktree " + handle.branchName()
                        : commitMessagePrefix + " " + handle.branchName();
                if (commitWorktree(handle, message)) {
                    changedHandles.add(handle);
                }
            }
            if (changedHandles.isEmpty()) {
                return BatchMergeResult.nothing();
            }

            Path projectRoot = root.toAbsolutePath().normalize();
            Path parent = projectRoot.getParent() == null ? projectRoot : projectRoot.getParent();
            String token = UUID.randomUUID().toString().replace("-", "");
            Path integrationPath = parent.resolve(".mindcli-worktrees")
                    .resolve("integration")
                    .resolve(token);
            String integrationBranch = "mindcli-integration-" + token;
            integration = create(projectRoot, integrationPath, integrationBranch);

            for (WorktreeHandle handle : changedHandles) {
                CmdResult merge = runGit(integration.path(), "merge", "--no-edit", handle.branchName());
                if (merge.exitCode() != 0) {
                    List<String> conflicts = conflictingFiles(integration.path());
                    runGit(integration.path(), "merge", "--abort");
                    return BatchMergeResult.conflicting(conflicts);
                }
            }

            CmdResult promote = runGit(projectRoot, "merge", "--ff-only", integration.branchName());
            if (promote.exitCode() != 0) {
                throw new IOException("integration 合并回主工作区失败: " + promote.stderr());
            }
            return BatchMergeResult.clean();
        } finally {
            if (integration != null) {
                dispose(root, integration);
            }
            for (WorktreeHandle handle : validHandles) {
                dispose(root, handle);
            }
        }
    }

    /** 移除 worktree（强制）、删除其分支并清理残留目录。 */
    public synchronized void dispose(Path root, WorktreeHandle handle) {
        if (handle == null) {
            return;
        }
        runGit(root, "worktree", "remove", "--force", handle.path().toString());
        runGit(root, "worktree", "prune");
        if (handle.branchName() != null && !handle.branchName().isBlank()) {
            runGit(root, "branch", "-D", handle.branchName());
        }
        deleteRecursively(handle.path());
    }

    private List<String> conflictingFiles(Path root) {
        CmdResult diff = runGit(root, "diff", "--name-only", "--diff-filter=U");
        if (diff.exitCode() != 0 || diff.stdout().isBlank()) {
            return List.of();
        }
        return diff.stdout().lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .sorted()
                .toList();
    }

    private boolean commitWorktree(WorktreeHandle handle, String commitMessage) throws IOException {
        CmdResult add = runGit(handle.path(), "add", "-A");
        if (add.exitCode() != 0) {
            throw new IOException("worktree git add 失败: " + add.stderr());
        }
        CmdResult status = runGit(handle.path(), "status", "--porcelain");
        if (status.exitCode() != 0) {
            throw new IOException("worktree git status 失败: " + status.stderr());
        }
        if (status.stdout().isBlank()) {
            return false;
        }
        CmdResult commit = runGit(handle.path(), "commit", "-m", commitMessage);
        if (commit.exitCode() != 0) {
            throw new IOException("worktree git commit 失败: " + commit.stderr());
        }
        return true;
    }

    private static CmdResult runGit(Path cwd, String... args) {
        List<String> command = new ArrayList<>(args.length + 1);
        command.add("git");
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        if (cwd != null) {
            builder.directory(cwd.toFile());
        }
        builder.redirectErrorStream(false);
        try {
            Process process = builder.start();
            StreamGobbler stdout = new StreamGobbler(process.getInputStream());
            StreamGobbler stderr = new StreamGobbler(process.getErrorStream());
            stdout.start();
            stderr.start();
            boolean finished = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                return new CmdResult(-1, stdout.content(), "git 命令超时");
            }
            stdout.join(2000);
            stderr.join(2000);
            return new CmdResult(process.exitValue(), stdout.content(), stderr.content());
        } catch (IOException e) {
            log.debug("git 命令不可用: {}", command, e);
            return new CmdResult(-1, "", "git 命令不可用: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CmdResult(-1, "", "git 命令被中断");
        }
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.debug("Failed to delete {}", path, e);
                }
            }
        } catch (IOException e) {
            log.debug("Failed to walk {}", root, e);
        }
    }

    private record CmdResult(int exitCode, String stdout, String stderr) {
    }

    /** 后台读取子进程输出，避免管道阻塞。 */
    private static final class StreamGobbler {
        private final InputStream stream;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final Thread thread;

        StreamGobbler(InputStream stream) {
            this.stream = stream;
            this.thread = new Thread(this::drain, "mindcli-git-gobbler");
            this.thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        void join(long millis) throws InterruptedException {
            thread.join(millis);
        }

        String content() {
            return buffer.toString(StandardCharsets.UTF_8);
        }

        private void drain() {
            byte[] chunk = new byte[4096];
            try {
                int read;
                while ((read = stream.read(chunk)) != -1) {
                    buffer.write(chunk, 0, read);
                }
            } catch (IOException e) {
                log.debug("读取 git 输出失败", e);
            } finally {
                try {
                    stream.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** worktree 句柄。 */
    public record WorktreeHandle(Path path, String branchName) {
        public WorktreeHandle {
            if (path == null) {
                throw new IllegalArgumentException("path");
            }
            branchName = branchName == null ? "" : branchName;
        }
    }

    /** 批次集成结果。冲突时主工作区仍停留在批次开始前的基线。 */
    public record BatchMergeResult(Status status, List<String> conflictingFiles) {
        public enum Status {
            CLEAN,
            NOTHING,
            CONFLICTING
        }

        public BatchMergeResult {
            conflictingFiles = conflictingFiles == null ? List.of() : List.copyOf(conflictingFiles);
        }

        static BatchMergeResult clean() {
            return new BatchMergeResult(Status.CLEAN, List.of());
        }

        static BatchMergeResult nothing() {
            return new BatchMergeResult(Status.NOTHING, List.of());
        }

        static BatchMergeResult conflicting(List<String> files) {
            return new BatchMergeResult(Status.CONFLICTING, files);
        }
    }

    /** worktree 合并结果。 */
    public record WorktreeMergeResult(Status status, List<String> conflictingFiles) {
        public enum Status {
            /** 合并成功，worktree 已清理。 */
            CLEAN,
            /** worktree 无变更，未产生提交。 */
            NOTHING,
            /** 合并冲突，conflictingFiles 为冲突文件清单（主工作区已回滚到合并前）。 */
            CONFLICTING,
            /** 合并失败（非冲突）。 */
            FAILED
        }

        public WorktreeMergeResult {
            conflictingFiles = conflictingFiles == null ? List.of() : List.copyOf(conflictingFiles);
        }

        static WorktreeMergeResult clean() {
            return new WorktreeMergeResult(Status.CLEAN, List.of());
        }

        static WorktreeMergeResult nothing() {
            return new WorktreeMergeResult(Status.NOTHING, List.of());
        }

        static WorktreeMergeResult conflicting(List<String> files) {
            return new WorktreeMergeResult(Status.CONFLICTING, files);
        }

        static WorktreeMergeResult failed(String reason) {
            return new WorktreeMergeResult(Status.FAILED, List.of(reason));
        }
    }
}
