package com.mindcli.skill;

import java.nio.file.Path;
import java.util.List;

/**
 * 一个 Skill 是 MindCLI 沉淀决策与经验的复用单元。
 *
 * 由 SKILL.md 文件解析得到：frontmatter 决定索引段元数据，
 * body 在 LLM 调用 load_skill 时作为 tool_result 直接返回并立即生效。
 *
 * source 标记加载来源，用于 /skill list 展示与三层覆盖的可观测性。
 */
public record Skill(
        String name,
        String description,
        String version,
        String author,
        List<String> tags,
        Source source,
        String body,
        Path skillMdPath,
        Path referencesDir,
        String whenToUse
) {

    public enum Source {
        BUILTIN, USER, PROJECT
    }

    public Skill {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Skill name 不能为空");
        }
        if (description == null) {
            description = "";
        }
        if (tags == null) {
            tags = List.of();
        } else {
            tags = List.copyOf(tags);
        }
        if (body == null) {
            body = "";
        }
        if (whenToUse == null) {
            whenToUse = "";
        }
    }

    public String displaySource() {
        return switch (source) {
            case BUILTIN -> "builtin";
            case USER -> "user";
            case PROJECT -> "project";
        };
    }
}
