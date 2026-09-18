package com.mindcli.agent.plan;

import com.mindcli.runtime.run.dispatch.ToolResourceClassifier;

import java.util.ArrayList;
import java.util.List;

public final class PlanTaskScheduler {
    private PlanTaskScheduler() {
    }

    public static Schedule schedule(List<Task> readyTasks) {
        List<Task> parallelReadOnly = new ArrayList<>();
        List<Task> serial = new ArrayList<>();
        if (readyTasks != null) {
            for (Task task : readyTasks) {
                if (isExplicitlyReadOnly(task)) {
                    parallelReadOnly.add(task);
                } else if (task != null) {
                    serial.add(task);
                }
            }
        }
        return new Schedule(parallelReadOnly, serial);
    }

    private static boolean isExplicitlyReadOnly(Task task) {
        if (task == null || task.getType() == Task.TaskType.FILE_WRITE
                || task.getType() == Task.TaskType.COMMAND) {
            return false;
        }
        List<String> tools = task.getRequiredTools();
        if (tools.isEmpty()) {
            return task.getType() == Task.TaskType.FILE_READ;
        }
        return tools.stream().allMatch(ToolResourceClassifier::isExplicitReadOnlyToolName);
    }

    public record Schedule(List<Task> parallelReadOnly, List<Task> serial) {
        public Schedule {
            parallelReadOnly = parallelReadOnly == null ? List.of() : List.copyOf(parallelReadOnly);
            serial = serial == null ? List.of() : List.copyOf(serial);
        }
    }
}
