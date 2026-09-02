package com.mindcli.runtime.run.session;
import com.mindcli.runtime.run.*;
import com.mindcli.runtime.run.dispatch.*;
import com.mindcli.runtime.run.hook.*;
import com.mindcli.runtime.run.legacy.*;
import com.mindcli.runtime.run.loop.*;
import com.mindcli.runtime.run.mode.*;
import com.mindcli.runtime.run.recovery.*;
import com.mindcli.runtime.run.session.*;
import com.mindcli.runtime.run.store.*;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionContextTest {

    @Test
    void keepsRecentRunSummariesAndCompactsOlderRuns() {
        SessionContext session = new SessionContext(2, 80);

        session.record(success(AgentMode.PLAN, "实现登录接口已完成"));
        session.record(success(AgentMode.TEAM, "单元测试已完成"));
        session.record(success(AgentMode.REACT, "数据库配置已修复"));

        String prompt = session.promptContext(200);

        assertTrue(prompt.contains("数据库配置已修复"));
        assertTrue(prompt.contains("单元测试已完成"));
        assertTrue(prompt.contains("早期运行摘要"));
        assertTrue(session.recentSummaries().stream()
                .noneMatch(summary -> summary.content().contains("实现登录接口已完成")));
        assertTrue(session.compactedSummary().contains("实现登录接口已完成"));
    }

    @Test
    void recordsRunResultForTheNextModeInTheSameSession() {
        SessionContext session = new SessionContext();

        session.record(success(AgentMode.PLAN, "AuthService.java 已修改，缺少集成测试"));

        String nextRunContext = session.promptContext(1_000);

        assertTrue(nextRunContext.contains("PLAN"));
        assertTrue(nextRunContext.contains("AuthService.java 已修改"));
    }

    @Test
    void boundsLargeRunResultsBeforeKeepingThemInSessionContext() {
        SessionContext session = new SessionContext();
        session.record(success(AgentMode.TEAM, "x".repeat(20_000)));

        assertTrue(session.recentSummaries().get(0).content().length() < 3_000);
    }

    @Test
    void clearRemovesCrossRunContext() {
        SessionContext session = new SessionContext();
        session.record(success(AgentMode.REACT, "旧任务结果"));

        session.clear();

        assertTrue(session.recentSummaries().isEmpty());
        assertTrue(session.compactedSummary().isBlank());
        assertTrue(session.promptContext(1_000).isBlank());
    }

    @Test
    void promptBudgetPrioritizesTheMostRecentRun() {
        SessionContext session = new SessionContext(1, 80);
        session.record(success(AgentMode.PLAN, "早期结果"));
        session.record(success(AgentMode.TEAM, "最新结果"));

        assertTrue(session.promptContext(30).contains("最新结果"));
    }

    private static AgentRunResult success(AgentMode mode, String content) {
        AgentRunContext context = AgentRunContext.create(mode, "input", "workspace");
        return AgentRunResult.success(context, content);
    }
}
