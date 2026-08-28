package com.mindcli.platform.worktree;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GitWorktreeManagerTest {

    @TempDir
    Path tempDir;

    private GitWorktreeManager manager;

    @BeforeEach
    void setUp() {
        manager = new GitWorktreeManager();
        assumeTrue(manager.isGitAvailable(), "系统未安装 git，跳过 worktree 测试");
    }

    @Test
    void isGitRepository_returnsTrueForGitRepo() throws Exception {
        Path repo = tempDir.resolve("repo");
        initRepo(repo);

        assertTrue(manager.isGitRepository(repo));
    }

    @Test
    void isGitRepository_returnsFalseForPlainDir() {
        assertFalse(manager.isGitRepository(tempDir.resolve("not-a-repo")));
    }

    @Test
    void createAndDispose_cleansUpWorktree() throws Exception {
        Path repo = tempDir.resolve("repo");
        initRepo(repo);
        Path worktree = tempDir.resolve("wt");

        GitWorktreeManager.WorktreeHandle handle = manager.create(repo, worktree, "wt-branch");

        assertTrue(Files.isDirectory(worktree), "worktree 目录应存在");
        assertEquals("wt-branch", handle.branchName());
        assertTrue(Files.exists(worktree.resolve("a.txt")), "worktree 应包含主仓库文件");

        manager.dispose(repo, handle);

        assertFalse(Files.exists(worktree), "dispose 后 worktree 目录应被清理");
    }

    @Test
    void mergeAndDispose_withNoChanges_returnsNothing() throws Exception {
        Path repo = tempDir.resolve("repo");
        initRepo(repo);
        Path worktree = tempDir.resolve("wt");
        GitWorktreeManager.WorktreeHandle handle = manager.create(repo, worktree, "empty-branch");

        GitWorktreeManager.WorktreeMergeResult result = manager.mergeAndDispose(repo, handle, "no changes");

        assertEquals(GitWorktreeManager.WorktreeMergeResult.Status.NOTHING, result.status());
        assertFalse(Files.exists(worktree));
    }

    @Test
    void mergeTwoDisjointWorktrees_mergesCleanly() throws Exception {
        Path repo = tempDir.resolve("repo");
        initRepo(repo);

        Path wt1 = tempDir.resolve("wt1");
        Path wt2 = tempDir.resolve("wt2");
        GitWorktreeManager.WorktreeHandle h1 = manager.create(repo, wt1, "branch-1");
        GitWorktreeManager.WorktreeHandle h2 = manager.create(repo, wt2, "branch-2");

        Files.writeString(wt1.resolve("one.txt"), "one\n");
        Files.writeString(wt2.resolve("two.txt"), "two\n");

        GitWorktreeManager.WorktreeMergeResult r1 = manager.mergeAndDispose(repo, h1, "one");
        GitWorktreeManager.WorktreeMergeResult r2 = manager.mergeAndDispose(repo, h2, "two");

        assertEquals(GitWorktreeManager.WorktreeMergeResult.Status.CLEAN, r1.status());
        assertEquals(GitWorktreeManager.WorktreeMergeResult.Status.CLEAN, r2.status());
        assertEquals("one", Files.readString(repo.resolve("one.txt")).strip());
        assertEquals("two", Files.readString(repo.resolve("two.txt")).strip());
        assertFalse(Files.exists(wt1));
        assertFalse(Files.exists(wt2));
    }

    @Test
    void mergeSameFileFromTwoWorktrees_reportsConflictWithoutSilentOverwrite() throws Exception {
        Path repo = tempDir.resolve("repo");
        initRepo(repo);

        // 两个 worktree 都基于同一 base 提交创建，随后各自修改同一个文件
        Path wt1 = tempDir.resolve("wt1");
        Path wt2 = tempDir.resolve("wt2");
        GitWorktreeManager.WorktreeHandle h1 = manager.create(repo, wt1, "branch-1");
        GitWorktreeManager.WorktreeHandle h2 = manager.create(repo, wt2, "branch-2");

        Files.writeString(wt1.resolve("a.txt"), "change-1\n");
        Files.writeString(wt2.resolve("a.txt"), "change-2\n");

        // 第一个合并干净通过
        GitWorktreeManager.WorktreeMergeResult r1 = manager.mergeAndDispose(repo, h1, "one");
        assertEquals(GitWorktreeManager.WorktreeMergeResult.Status.CLEAN, r1.status());

        // 第二个合并遇到冲突，必须报告而非静默覆盖
        GitWorktreeManager.WorktreeMergeResult r2 = manager.mergeAndDispose(repo, h2, "two");

        assertEquals(GitWorktreeManager.WorktreeMergeResult.Status.CONFLICTING, r2.status());
        assertTrue(r2.conflictingFiles().contains("a.txt"), "冲突文件清单应包含 a.txt: " + r2.conflictingFiles());
        // 主工作区回滚到第一个合并后的状态
        assertEquals("change-1", Files.readString(repo.resolve("a.txt")).strip());
        // 冲突后 worktree 应被清理，不残留目录
        assertFalse(Files.exists(wt2), "冲突后 worktree 目录应被清理");
    }

    private static void initRepo(Path root) throws Exception {
        Files.createDirectories(root);
        try (Git git = Git.init().setDirectory(root.toFile()).call()) {
            git.getRepository().getConfig().setString("user", null, "name", "test");
            git.getRepository().getConfig().setString("user", null, "email", "test@example.com");
            git.getRepository().getConfig().save();
            Files.writeString(root.resolve("a.txt"), "base\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("init").call();
        }
    }
}
