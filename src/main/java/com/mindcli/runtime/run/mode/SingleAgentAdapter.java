package com.mindcli.runtime.run.mode;
import com.mindcli.runtime.run.*;
import com.mindcli.runtime.run.dispatch.*;
import com.mindcli.runtime.run.hook.*;
import com.mindcli.runtime.run.legacy.*;
import com.mindcli.runtime.run.loop.*;
import com.mindcli.runtime.run.recovery.*;
import com.mindcli.runtime.run.session.*;
import com.mindcli.runtime.run.store.*;

import com.mindcli.agent.team.SubAgent;

import java.io.PrintStream;
import java.util.Objects;

/**
 * 单子代理直连运行适配器（/agent &lt;name&gt; &lt;任务&gt;）。
 *
 * 复用 TeamModeAdapter 的返回约定：SubAgent.run 返回的字符串以 ❌ 开头表示失败，
 * 否则视为成功正文。运行模式复用 TEAM，因为单子代理本质是 team 执行阶段的一个子执行单元。
 */
public final class SingleAgentAdapter implements ModeAdapter {
    private final ContextualLegacyAgentRunner runner;

    public SingleAgentAdapter(SubAgent agent, PrintStream out) {
        Objects.requireNonNull(agent, "agent");
        PrintStream stream = out == null ? System.out : out;
        this.runner = (context, runStore) -> agent.run(context.input(), stream, context, runStore);
    }

    @Override
    public AgentMode mode() {
        return AgentMode.TEAM;
    }

    @Override
    public AgentRunResult execute(AgentRunContext context) {
        return execute(context, null);
    }

    @Override
    public AgentRunResult execute(AgentRunContext context, RunStore runStore) {
        try {
            return resultFromContent(context, runner.run(context, runStore));
        } catch (Exception e) {
            return AgentRunResult.failed(context, errorMessage(e));
        }
    }

    private static AgentRunResult resultFromContent(AgentRunContext context, String content) {
        String normalized = content == null ? "" : content.trim();
        if (normalized.startsWith("⏹")) {
            return AgentRunResult.cancelled(context, content);
        }
        if (normalized.startsWith("❌")) {
            return AgentRunResult.failed(context, normalized);
        }
        if (normalized.startsWith("⚠️") || normalized.startsWith("⚠")) {
            return AgentRunResult.blocked(context, normalized);
        }
        return AgentRunResult.success(context, content);
    }

    private static String errorMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
