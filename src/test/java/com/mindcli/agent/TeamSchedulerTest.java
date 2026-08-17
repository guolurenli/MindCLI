package com.mindcli.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TeamSchedulerTest {

    private static List<ExecutionStep> leadersOf(ScheduleWave wave) {
        List<ExecutionStep> leaders = new ArrayList<>();
        wave.readOnly().forEach(group -> leaders.add(group.leader()));
        wave.mutating().forEach(group -> leaders.add(group.leader()));
        return leaders;
    }

    @Test
    void returnsEmptyWaveWhenNoWorkRemains() {
        ExecutionStep done = ExecutionStep.pending("step_1", "已完成", "FILE_READ", List.of())
                .withResult("ok");
        assertFalse(new TeamScheduler().nextWave(List.of(done)).hasWork());
    }

    @Test
    void returnsReadyStepsInDependencyOrder() {
        List<ExecutionStep> steps = new ArrayList<>(List.of(
                ExecutionStep.pending("step_1", "创建项目", "COMMAND", List.of()),
                ExecutionStep.pending("step_2", "验证结构", "VERIFICATION", List.of("step_1"))
        ));

        ScheduleWave wave = new TeamScheduler().nextWave(steps);
        assertEquals(List.of("step_1"), leadersOf(wave).stream().map(ExecutionStep::id).toList());

        steps.set(0, steps.get(0).withResult("已创建"));
        wave = new TeamScheduler().nextWave(steps);
        assertEquals(List.of("step_2"), leadersOf(wave).stream().map(ExecutionStep::id).toList());
    }

    @Test
    void failedDependencyBlocksDownstreamSteps() {
        ExecutionStep failed = ExecutionStep.pending("step_1", "失败", "FILE_READ", List.of())
                .withFailed("boom");
        ExecutionStep downstream = ExecutionStep.pending("step_2", "依赖失败", "FILE_READ", List.of("step_1"));
        assertFalse(new TeamScheduler().nextWave(List.of(failed, downstream)).hasWork());
    }

    @Test
    void skippedDependencySatisfiesDownstreamSteps() {
        ExecutionStep skipped = ExecutionStep.pending("step_1", "跳过", "FILE_READ", List.of())
                .withSkipped("跳过");
        ExecutionStep downstream = ExecutionStep.pending("step_2", "后续", "FILE_READ", List.of("step_1"));
        ScheduleWave wave = new TeamScheduler().nextWave(List.of(skipped, downstream));
        assertEquals(List.of("step_2"), leadersOf(wave).stream().map(ExecutionStep::id).toList());
    }

    @Test
    void deduplicatesIdenticalReadySteps() {
        ExecutionStep a = ExecutionStep.pending("step_1", "更新 README", "FILE_WRITE", List.of(),
                List.of("write_file"), "", "low", List.of("src/**"));
        ExecutionStep b = ExecutionStep.pending("step_2", "更新 README", "FILE_WRITE", List.of(),
                List.of("write_file"), "", "low", List.of("src/**"));

        ScheduleWave wave = new TeamScheduler().nextWave(List.of(a, b));
        assertEquals(1, wave.mutating().size());
        StepExecutionGroup group = wave.mutating().get(0);
        assertEquals("step_1", group.leader().id());
        assertEquals(List.of("step_2"), group.duplicates().stream().map(ExecutionStep::id).toList());
    }

    @Test
    void partitionsReadOnlyAndMutatingGroups() {
        ExecutionStep read = ExecutionStep.pending("step_r", "读取", "FILE_READ", List.of());
        ExecutionStep write = ExecutionStep.pending("step_w", "写入", "FILE_WRITE", List.of(),
                List.of("write_file"), "", "low", List.of("src/**"));

        ScheduleWave wave = new TeamScheduler().nextWave(List.of(read, write));
        assertEquals(List.of("step_r"), wave.readOnly().stream().map(g -> g.leader().id()).toList());
        assertEquals(List.of("step_w"), wave.mutating().stream().map(g -> g.leader().id()).toList());
    }

    @Test
    void overlappingScopesProduceSerialReason() {
        ExecutionStep a = ExecutionStep.pending("step_a", "写 A", "FILE_WRITE", List.of(),
                List.of("write_file"), "", "low", List.of("src/a/**"));
        ExecutionStep b = ExecutionStep.pending("step_b", "写 B", "FILE_WRITE", List.of(),
                List.of("write_file"), "", "low", List.of("src/a/b/**"));

        ScheduleWave wave = new TeamScheduler().nextWave(List.of(a, b));
        assertTrue(wave.serialReasons().containsKey("step_a"));
        assertTrue(wave.serialReasons().get("step_a").contains("写入范围重叠"));
    }

    @Test
    void undefinedScopeProducesSerialReason() {
        ExecutionStep a = ExecutionStep.pending("step_a", "写 A", "FILE_WRITE", List.of(),
                List.of("write_file"), "", "low", List.of());

        ScheduleWave wave = new TeamScheduler().nextWave(List.of(a));
        assertTrue(wave.serialReasons().containsKey("step_a"));
        assertTrue(wave.serialReasons().get("step_a").contains("写入范围未声明"));
    }

    @Test
    void nonOverlappingScopesProduceNoSerialReason() {
        ExecutionStep a = ExecutionStep.pending("step_a", "写 A", "FILE_WRITE", List.of(),
                List.of("write_file"), "", "low", List.of("src/a/**"));
        ExecutionStep b = ExecutionStep.pending("step_b", "写 B", "FILE_WRITE", List.of(),
                List.of("write_file"), "", "low", List.of("src/b/**"));

        ScheduleWave wave = new TeamScheduler().nextWave(List.of(a, b));
        assertTrue(wave.serialReasons().isEmpty());
    }

    @Test
    void serialReasonIsWaveLevelVeto() {
        ExecutionStep a = ExecutionStep.pending("step_a", "写 A", "FILE_WRITE", List.of(),
                List.of("write_file"), "", "low", List.of("src/a/**"));
        ExecutionStep b = ExecutionStep.pending("step_b", "写 B", "FILE_WRITE", List.of(),
                List.of("write_file"), "", "low", List.of());

        ScheduleWave wave = new TeamScheduler().nextWave(List.of(a, b));
        // b 未声明 scope 触发串行原因；a 仍在同一波 mutating 中，由 orchestrator 整波串行。
        assertEquals(2, wave.mutating().size());
        assertTrue(wave.serialReasons().containsKey("step_b"));
    }
}
