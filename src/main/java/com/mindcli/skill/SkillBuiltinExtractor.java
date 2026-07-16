package com.mindcli.skill;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 把 jar 内 resources/skills/&lt;name&gt;/ 解压到 ~/.mindcli/skills-cache/&lt;name&gt;/。
 *
 * 解压策略：通过 .version 文件标记当前 jar 内置版本。版本一致跳过；不一致或缺失则覆盖整个目录。
 *
 * 内置 skill 文件清单为硬编码（避免 jar 内 resource walk 的跨平台问题）。
 */
public final class SkillBuiltinExtractor {

    /** 内置 skill 版本，有增删 skill 时上调以触发缓存重建。 */
    public static final String CURRENT_VERSION = "2.0.0";

    private static final List<BuiltinSkillSpec> BUILTIN_SKILLS = List.of(
            // ---- 独立内置 skills ----
            new BuiltinSkillSpec("web-access", List.of(
                    "SKILL.md",
                    "references/cdp-cheatsheet.md",
                    "references/site-patterns/github.com.md",
                    "references/site-patterns/juejin.cn.md",
                    "references/site-patterns/mp.weixin.qq.com.md",
                    "references/site-patterns/x.com.md",
                    "references/site-patterns/xiaohongshu.com.md",
                    "references/site-patterns/zhuanlan.zhihu.com.md"
            )),

            // ---- superpowers 子 skills ----
            new BuiltinSkillSpec("brainstorming", "skills/superpowers/brainstorming/", List.of(
                    "SKILL.md",
                    "scripts/frame-template.html",
                    "scripts/helper.js",
                    "scripts/server.cjs",
                    "scripts/start-server.sh",
                    "scripts/stop-server.sh",
                    "spec-document-reviewer-prompt.md",
                    "visual-companion.md"
            )),
            new BuiltinSkillSpec("dispatching-parallel-agents", "skills/superpowers/dispatching-parallel-agents/", List.of(
                    "SKILL.md"
            )),
            new BuiltinSkillSpec("executing-plans", "skills/superpowers/executing-plans/", List.of(
                    "SKILL.md"
            )),
            new BuiltinSkillSpec("finishing-a-development-branch", "skills/superpowers/finishing-a-development-branch/", List.of(
                    "SKILL.md"
            )),
            new BuiltinSkillSpec("receiving-code-review", "skills/superpowers/receiving-code-review/", List.of(
                    "SKILL.md"
            )),
            new BuiltinSkillSpec("requesting-code-review", "skills/superpowers/requesting-code-review/", List.of(
                    "SKILL.md",
                    "code-reviewer.md"
            )),
            new BuiltinSkillSpec("subagent-driven-development", "skills/superpowers/subagent-driven-development/", List.of(
                    "SKILL.md",
                    "implementer-prompt.md",
                    "scripts/review-package",
                    "scripts/sdd-workspace",
                    "scripts/task-brief",
                    "task-reviewer-prompt.md"
            )),
            new BuiltinSkillSpec("systematic-debugging", "skills/superpowers/systematic-debugging/", List.of(
                    "CREATION-LOG.md",
                    "SKILL.md",
                    "condition-based-waiting-example.ts",
                    "condition-based-waiting.md",
                    "defense-in-depth.md",
                    "find-polluter.sh",
                    "root-cause-tracing.md",
                    "test-academic.md",
                    "test-pressure-1.md",
                    "test-pressure-2.md",
                    "test-pressure-3.md"
            )),
            new BuiltinSkillSpec("test-driven-development", "skills/superpowers/test-driven-development/", List.of(
                    "SKILL.md",
                    "testing-anti-patterns.md"
            )),
            new BuiltinSkillSpec("using-git-worktrees", "skills/superpowers/using-git-worktrees/", List.of(
                    "SKILL.md"
            )),
            new BuiltinSkillSpec("using-superpowers", "skills/superpowers/using-superpowers/", List.of(
                    "SKILL.md",
                    "references/antigravity-tools.md",
                    "references/codex-tools.md",
                    "references/pi-tools.md"
            )),
            new BuiltinSkillSpec("verification-before-completion", "skills/superpowers/verification-before-completion/", List.of(
                    "SKILL.md"
            )),
            new BuiltinSkillSpec("writing-plans", "skills/superpowers/writing-plans/", List.of(
                    "SKILL.md",
                    "plan-document-reviewer-prompt.md"
            )),
            new BuiltinSkillSpec("writing-skills", "skills/superpowers/writing-skills/", List.of(
                    "SKILL.md",
                    "anthropic-best-practices.md",
                    "examples/CLAUDE_MD_TESTING.md",
                    "graphviz-conventions.dot",
                    "persuasion-principles.md",
                    "render-graphs.js",
                    "testing-skills-with-subagents.md"
            ))
    );

    private final Path cacheRoot;

    public SkillBuiltinExtractor(Path cacheRoot) {
        this.cacheRoot = cacheRoot;
    }

    public Path cacheRoot() {
        return cacheRoot;
    }

    public List<String> builtinSkillNames() {
        return BUILTIN_SKILLS.stream().map(BuiltinSkillSpec::name).toList();
    }

    public Path skillCacheDir(String skillName) {
        return cacheRoot.resolve(skillName);
    }

    public void extractAll() throws IOException {
        Files.createDirectories(cacheRoot);
        for (BuiltinSkillSpec spec : BUILTIN_SKILLS) {
            extract(spec);
        }
    }

    private void extract(BuiltinSkillSpec spec) throws IOException {
        Path skillDir = cacheRoot.resolve(spec.name());
        Path versionFile = skillDir.resolve(".version");
        if (Files.exists(versionFile)) {
            String existing = Files.readString(versionFile).trim();
            if (CURRENT_VERSION.equals(existing)) {
                return;
            }
        }
        if (Files.exists(skillDir)) {
            deleteRecursive(skillDir);
        }
        Files.createDirectories(skillDir);
        for (String relative : spec.files()) {
            String resourcePath = spec.resourceBasePath() + relative;
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                if (in == null) {
                    System.err.println("⚠️ 内置 skill 资源缺失: " + resourcePath);
                    continue;
                }
                Path target = skillDir.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.writeString(versionFile, CURRENT_VERSION);
    }

    private static void deleteRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    private record BuiltinSkillSpec(String name, String resourceBasePath, List<String> files) {
        BuiltinSkillSpec(String name, List<String> files) {
            this(name, "skills/" + name + "/", files);
        }
    }
}
