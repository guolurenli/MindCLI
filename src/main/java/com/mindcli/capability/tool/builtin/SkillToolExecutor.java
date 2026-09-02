package com.mindcli.capability.tool.builtin;

import com.mindcli.capability.skill.Skill;
import com.mindcli.capability.skill.SkillRegistry;

import java.util.Map;

/** Executes the built-in skill loading tool. */
public final class SkillToolExecutor {
    private static final int MAX_BODY_CHARS = 5 * 1024;
    private final SkillRegistry skillRegistry;

    public SkillToolExecutor(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    public String load(Map<String, String> args) {
        String name = args == null ? null : args.get("name");
        if (name == null || name.isBlank()) {
            return "load_skill 失败: name 不能为空";
        }
        if (skillRegistry == null) {
            return "load_skill 失败: Skill 系统未初始化";
        }
        Skill skill = skillRegistry.findSkill(name);
        if (skill == null) {
            Skill any = skillRegistry.findAnySkill(name);
            if (any == null) {
                return "Skill '" + name + "' 未找到，可用 /skill list 查看可用 skill";
            }
            return "Skill '" + name + "' 已被禁用，可用 /skill on " + name + " 启用";
        }
        String body = skill.body() == null ? "" : skill.body();
        if (body.length() > MAX_BODY_CHARS) {
            body = body.substring(0, MAX_BODY_CHARS)
                    + "\n\n...(skill body truncated, full content via /skill show " + name + ")";
        }
        return "## 已加载 Skill：" + name + "\n\n" + body;
    }
}
