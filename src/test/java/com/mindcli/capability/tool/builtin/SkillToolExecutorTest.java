package com.mindcli.capability.tool.builtin;

import com.mindcli.capability.skill.SkillRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillToolExecutorTest {
    @Test
    void rejectsMissingNameWithoutInitializedRegistry() {
        String result = new SkillToolExecutor(null).load(Map.of());

        assertTrue(result.contains("name 不能为空"), result);
    }

    @Test
    void rejectsAnySkillWhenRegistryIsNotInitialized() {
        String result = new SkillToolExecutor(null).load(Map.of("name", "web-access"));

        assertTrue(result.contains("Skill 系统未初始化"), result);
    }

    @Test
    void rejectsUnknownSkillFromInitializedRegistry() {
        String result = new SkillToolExecutor(new SkillRegistry(null, null, null, null))
                .load(Map.of("name", "missing"));

        assertTrue(result.contains("未找到"), result);
    }
}
