package com.mindcli.agent;

/** 执行步骤的生命周期状态（package-private 内部模型）。 */
enum StepStatus {
    PENDING, RUNNING, COMPLETED, FAILED, SKIPPED
}
