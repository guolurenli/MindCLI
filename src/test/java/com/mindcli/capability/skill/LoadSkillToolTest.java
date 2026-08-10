package com.mindcli.capability.skill;

import com.mindcli.capability.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LoadSkillToolTest {

    @Test
    void loadsExistingSkillReturnsBodyDirectly(@TempDir Path tempDir) throws IOException {
        SkillRegistry registry = registryWith(tempDir, "web-access", "决策手册",
                "# Body\nwhen to fetch\nwhen to browse\n");
        ToolRegistry tools = new ToolRegistry();
        tools.setSkillRegistry(registry);

        String result = tools.executeTool("load_skill", "{\"name\":\"web-access\"}");

        assertTrue(result.contains("## 已加载 Skill：web-access"), result);
        assertTrue(result.contains("when to fetch"));
        assertTrue(result.contains("when to browse"));
    }

    @Test
    void failsForUnknownSkill(@TempDir Path tempDir) throws IOException {
        SkillRegistry registry = registryWith(tempDir, "real-one", "desc", "body");
        ToolRegistry tools = new ToolRegistry();
        tools.setSkillRegistry(registry);

        String result = tools.executeTool("load_skill", "{\"name\":\"nonexistent\"}");
        assertTrue(result.contains("未找到"), result);
    }

    @Test
    void failsForDisabledSkill(@TempDir Path tempDir) throws IOException {
        Path stateFile = tempDir.resolve("skills.json");
        SkillStateStore state = new SkillStateStore(stateFile);
        state.disable("web-access");

        SkillRegistry registry = new SkillRegistry(null,
                writeUserSkill(tempDir, "web-access", "desc", "body").getParent().getParent(),
                null, state);
        registry.reload();

        ToolRegistry tools = new ToolRegistry();
        tools.setSkillRegistry(registry);

        String result = tools.executeTool("load_skill", "{\"name\":\"web-access\"}");
        assertTrue(result.contains("已被禁用"), result);
    }

    @Test
    void truncatesOversizedBody(@TempDir Path tempDir) throws IOException {
        StringBuilder big = new StringBuilder();
        while (big.length() < 6 * 1024) big.append("0123456789");
        SkillRegistry registry = registryWith(tempDir, "huge", "desc", big.toString());

        ToolRegistry tools = new ToolRegistry();
        tools.setSkillRegistry(registry);

        String result = tools.executeTool("load_skill", "{\"name\":\"huge\"}");
        assertTrue(result.contains("(skill body truncated"), "应包含截断标记");
    }

    @Test
    void failsWhenNameMissing() {
        ToolRegistry tools = new ToolRegistry();
        tools.setSkillRegistry(new SkillRegistry(null, null, null, null));

        String result = tools.executeTool("load_skill", "{}");
        assertTrue(result.contains("name 不能为空"), result);
    }

    private static SkillRegistry registryWith(Path tempDir, String name, String desc, String body) throws IOException {
        Path userRoot = writeUserSkill(tempDir, name, desc, body).getParent().getParent();
        SkillStateStore state = new SkillStateStore(tempDir.resolve("skills.json"));
        SkillRegistry registry = new SkillRegistry(null, userRoot, null, state);
        registry.reload();
        return registry;
    }

    private static Path writeUserSkill(Path tempDir, String name, String desc, String body) throws IOException {
        Path userRoot = tempDir.resolve("user-skills");
        Path skillDir = userRoot.resolve(name);
        Files.createDirectories(skillDir);
        Path skillMd = skillDir.resolve("SKILL.md");
        Files.writeString(skillMd,
                "---\nname: " + name
                        + "\ndescription: " + desc
                        + "\n---\n" + body + "\n");
        return skillMd;
    }
}
