package com.mindcli.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentMessageTest {

    @Test
    void shouldCreateTaskMessage() {
        AgentMessage msg = AgentMessage.task("orchestrator", "分析代码结构");
        assertEquals("orchestrator", msg.fromAgent());
        assertNull(msg.fromRole());
        assertEquals("分析代码结构", msg.content());
        assertEquals(AgentMessage.Type.TASK, msg.type());
    }

    @Test
    void shouldCreateResultMessage() {
        AgentMessage msg = AgentMessage.result("worker-1", AgentRole.WORKER, "代码结构如下...");
        assertEquals("worker-1", msg.fromAgent());
        assertEquals(AgentRole.WORKER, msg.fromRole());
        assertEquals("代码结构如下...", msg.content());
        assertEquals(AgentMessage.Type.RESULT, msg.type());
    }

    @Test
    void shouldCreateErrorMessage() {
        AgentMessage msg = AgentMessage.error("worker-1", AgentRole.WORKER, "LLM 调用失败: 500");
        assertEquals("worker-1", msg.fromAgent());
        assertEquals(AgentRole.WORKER, msg.fromRole());
        assertEquals("LLM 调用失败: 500", msg.content());
        assertEquals(AgentMessage.Type.ERROR, msg.type());
    }

    @Test
    void shouldHaveThreeMessageTypes() {
        AgentMessage.Type[] types = AgentMessage.Type.values();
        assertEquals(3, types.length);
    }

    @Test
    void shouldValueOfTypeByName() {
        assertSame(AgentMessage.Type.TASK, AgentMessage.Type.valueOf("TASK"));
        assertSame(AgentMessage.Type.RESULT, AgentMessage.Type.valueOf("RESULT"));
        assertSame(AgentMessage.Type.ERROR, AgentMessage.Type.valueOf("ERROR"));
    }
}
