package com.mindcli.agent.plan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlanTaskSchedulerTest {

    @Test
    void onlyExplicitFileReadsEnterParallelWave() {
        Task read = new Task("read", "inspect source", Task.TaskType.FILE_READ);
        Task write = new Task("write", "update source", Task.TaskType.FILE_WRITE);
        Task command = new Task("command", "run command", Task.TaskType.COMMAND);
        Task analysis = new Task("analysis", "summarize", Task.TaskType.ANALYSIS);

        PlanTaskScheduler.Schedule schedule = PlanTaskScheduler.schedule(
                List.of(write, read, command, analysis));

        assertEquals(List.of("read"), schedule.parallelReadOnly().stream().map(Task::getId).toList());
        assertEquals(List.of("write", "command", "analysis"),
                schedule.serial().stream().map(Task::getId).toList());
    }

    @Test
    void fileReadWithUnknownOrMutatingRequiredToolRunsSerially() {
        Task safe = new Task("safe", "read", Task.TaskType.FILE_READ);
        safe.setRequiredTools(List.of("read_file", "grep_code"));
        Task shell = new Task("shell", "inspect through shell", Task.TaskType.FILE_READ);
        shell.setRequiredTools(List.of("execute_command"));
        Task custom = new Task("custom", "inspect through plugin", Task.TaskType.FILE_READ);
        custom.setRequiredTools(List.of("custom_tool"));

        PlanTaskScheduler.Schedule schedule = PlanTaskScheduler.schedule(List.of(safe, shell, custom));

        assertEquals(List.of("safe"), schedule.parallelReadOnly().stream().map(Task::getId).toList());
        assertEquals(List.of("shell", "custom"), schedule.serial().stream().map(Task::getId).toList());
    }
}
