package com.mindcli.agent.plan;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Business-neutral dependency DAG helper shared by Plan and Team modes.
 *
 * <p>The graph only knows node ids and dependency ids. Execution semantics
 * such as "which statuses satisfy a dependency" stay with each caller.</p>
 */
public final class DependencyGraph<T> {
    public record DependencyBlocker(String dependencyId, String state) {
    }

    public record BlockedNode<T>(T node, List<DependencyBlocker> blockingDependencies) {
    }

    private final List<T> nodes;
    private final Function<T, String> idProvider;
    private final Function<T, List<String>> dependencyProvider;
    private final Map<String, T> nodeById = new LinkedHashMap<>();
    private final List<String> issues = new ArrayList<>();

    private DependencyGraph(Collection<T> nodes,
                            Function<T, String> idProvider,
                            Function<T, List<String>> dependencyProvider) {
        this.nodes = List.copyOf(nodes == null ? List.of() : nodes);
        this.idProvider = Objects.requireNonNull(idProvider, "idProvider");
        this.dependencyProvider = Objects.requireNonNull(dependencyProvider, "dependencyProvider");
        validate();
    }

    public static <T> DependencyGraph<T> of(Collection<T> nodes,
                                            Function<T, String> idProvider,
                                            Function<T, List<String>> dependencyProvider) {
        return new DependencyGraph<>(nodes, idProvider, dependencyProvider);
    }

    public boolean isValid() {
        return issues.isEmpty();
    }

    public List<String> issues() {
        return List.copyOf(issues);
    }

    public List<T> topologicalOrder() {
        if (!isValid()) {
            return List.of();
        }

        List<T> ordered = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        for (T node : nodes) {
            String id = idOf(node);
            if (!visited.contains(id)) {
                topologicalSort(id, visited, visiting, ordered);
            }
        }
        return ordered;
    }

    public List<List<T>> executionBatches() {
        if (!isValid()) {
            return List.of();
        }

        Map<String, T> remaining = new LinkedHashMap<>(nodeById);
        Set<String> completed = new LinkedHashSet<>();
        List<List<T>> batches = new ArrayList<>();

        while (!remaining.isEmpty()) {
            List<T> batch = remaining.values().stream()
                    .filter(node -> dependenciesOf(node).stream().allMatch(completed::contains))
                    .toList();
            if (batch.isEmpty()) {
                return List.of();
            }
            batches.add(batch);
            for (T node : batch) {
                String id = idOf(node);
                remaining.remove(id);
                completed.add(id);
            }
        }

        return batches;
    }

    public List<T> readyNodes(Predicate<T> isPending,
                              Predicate<String> isDependencySatisfied) {
        Objects.requireNonNull(isPending, "isPending");
        Objects.requireNonNull(isDependencySatisfied, "isDependencySatisfied");

        return nodes.stream()
                .filter(isPending)
                .filter(node -> dependenciesOf(node).stream().allMatch(isDependencySatisfied))
                .toList();
    }

    public List<BlockedNode<T>> blockedNodes(Predicate<T> isPending,
                                             Function<String, String> dependencyStateProvider,
                                             Predicate<String> isDependencySatisfied) {
        Objects.requireNonNull(isPending, "isPending");
        Objects.requireNonNull(dependencyStateProvider, "dependencyStateProvider");
        Objects.requireNonNull(isDependencySatisfied, "isDependencySatisfied");

        List<BlockedNode<T>> blockedNodes = new ArrayList<>();
        for (T node : nodes) {
            if (!isPending.test(node)) {
                continue;
            }
            List<DependencyBlocker> blockers = new ArrayList<>();
            for (String dependencyId : dependenciesOf(node)) {
                String state = dependencyStateProvider.apply(dependencyId);
                if (!isDependencySatisfied.test(state)) {
                    blockers.add(new DependencyBlocker(dependencyId, state == null ? "UNKNOWN" : state));
                }
            }
            if (!blockers.isEmpty()) {
                blockedNodes.add(new BlockedNode<>(node, List.copyOf(blockers)));
            }
        }
        return blockedNodes;
    }

    private void validate() {
        for (T node : nodes) {
            String id = idOf(node);
            if (id == null || id.isBlank()) {
                issues.add("节点 id 为空");
                continue;
            }
            if (nodeById.containsKey(id)) {
                issues.add("节点 id 重复: " + id);
                continue;
            }
            nodeById.put(id, node);
        }

        for (T node : nodes) {
            String nodeId = idOf(node);
            if (nodeId == null || nodeId.isBlank()) {
                continue;
            }
            for (String dependency : dependenciesOf(node)) {
                if (dependency == null || dependency.isBlank()) {
                    issues.add("依赖 id 为空: " + nodeId);
                    continue;
                }
                if (!nodeById.containsKey(dependency)) {
                    issues.add("依赖不存在: " + dependency + " -> " + nodeId);
                }
            }
        }

        if (issues.isEmpty() && hasCycle()) {
            issues.add("存在循环依赖");
        }
    }

    private boolean hasCycle() {
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String id : nodeById.keySet()) {
            if (detectCycle(id, visiting, visited)) {
                return true;
            }
        }
        return false;
    }

    private boolean detectCycle(String id, Set<String> visiting, Set<String> visited) {
        if (visited.contains(id)) {
            return false;
        }
        if (!visiting.add(id)) {
            return true;
        }
        T node = nodeById.get(id);
        if (node != null) {
            for (String dependency : dependenciesOf(node)) {
                if (nodeById.containsKey(dependency) && detectCycle(dependency, visiting, visited)) {
                    return true;
                }
            }
        }
        visiting.remove(id);
        visited.add(id);
        return false;
    }

    private void topologicalSort(String id, Set<String> visited, Set<String> visiting, List<T> ordered) {
        if (visited.contains(id)) {
            return;
        }
        if (!visiting.add(id)) {
            return;
        }
        T node = nodeById.get(id);
        if (node != null) {
            for (String dependency : dependenciesOf(node)) {
                topologicalSort(dependency, visited, visiting, ordered);
            }
            ordered.add(node);
        }
        visiting.remove(id);
        visited.add(id);
    }

    private String idOf(T node) {
        return idProvider.apply(node);
    }

    private List<String> dependenciesOf(T node) {
        List<String> dependencies = dependencyProvider.apply(node);
        return dependencies == null ? List.of() : dependencies;
    }
}
