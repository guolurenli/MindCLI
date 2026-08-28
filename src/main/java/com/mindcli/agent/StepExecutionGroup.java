package com.mindcli.agent;

import java.util.List;
import java.util.Objects;

/**
 * 按指纹去重后的一簇可执行步骤（package-private 内部模型）。
 *
 * <p>{@code leader} 是实际执行的步骤，{@code duplicates} 是与其指纹相同的其它步骤，
 * 其执行结果由 leader 传播而来。</p>
 */
record StepExecutionGroup(
        String fingerprint,
        ExecutionStep leader,
        List<ExecutionStep> duplicates,
        boolean mutating
) {
    StepExecutionGroup {
        fingerprint = fingerprint == null ? "" : fingerprint;
        leader = Objects.requireNonNull(leader, "leader");
        duplicates = duplicates == null ? List.of() : List.copyOf(duplicates);
    }
}
