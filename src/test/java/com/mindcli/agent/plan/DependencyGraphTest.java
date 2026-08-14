package com.mindcli.agent.plan;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyGraphTest {

    private record Node(String id, List<String> dependencies, Status status) {
    }

    private enum Status {
        PENDING,
        RUNNING,
        COMPLETED,
        SKIPPED,
        FAILED
    }

    @Test
    void topologicalOrderRespectsDependencies() {
        Node first = new Node("first", List.of(), Status.PENDING);
        Node second = new Node("second", List.of("first"), Status.PENDING);
        Node third = new Node("third", List.of("second"), Status.PENDING);

        DependencyGraph<Node> graph = DependencyGraph.of(
                List.of(third, first, second),
                Node::id,
                Node::dependencies);

        assertTrue(graph.isValid());
        assertEquals(List.of(first, second, third), graph.topologicalOrder());
    }

    @Test
    void executionBatchesFollowDagLayers() {
        Node readPom = new Node("read_pom", List.of(), Status.PENDING);
        Node listSrc = new Node("list_src", List.of(), Status.PENDING);
        Node summarize = new Node("summarize", List.of("read_pom", "list_src"), Status.PENDING);
        Node verify = new Node("verify", List.of("summarize"), Status.PENDING);

        DependencyGraph<Node> graph = DependencyGraph.of(
                List.of(readPom, listSrc, summarize, verify),
                Node::id,
                Node::dependencies);

        assertEquals(List.of(
                List.of(readPom, listSrc),
                List.of(summarize),
                List.of(verify)
        ), graph.executionBatches());
    }

    @Test
    void readyNodesUseCallerSuppliedStatusRules() {
        Node completed = new Node("completed", List.of(), Status.COMPLETED);
        Node skipped = new Node("skipped", List.of(), Status.SKIPPED);
        Node failed = new Node("failed", List.of(), Status.FAILED);
        Node readyAfterCompleted = new Node("ready_after_completed", List.of("completed"), Status.PENDING);
        Node readyAfterSkipped = new Node("ready_after_skipped", List.of("skipped"), Status.PENDING);
        Node blockedAfterFailed = new Node("blocked_after_failed", List.of("failed"), Status.PENDING);

        List<Node> nodes = List.of(
                completed,
                skipped,
                failed,
                readyAfterCompleted,
                readyAfterSkipped,
                blockedAfterFailed);
        DependencyGraph<Node> graph = DependencyGraph.of(nodes, Node::id, Node::dependencies);
        Predicate<String> dependencySatisfied = depId -> nodes.stream()
                .filter(node -> node.id().equals(depId))
                .map(Node::status)
                .anyMatch(status -> status == Status.COMPLETED || status == Status.SKIPPED);

        assertEquals(List.of(readyAfterCompleted, readyAfterSkipped),
                graph.readyNodes(node -> node.status() == Status.PENDING, dependencySatisfied));
    }

    @Test
    void missingDependencyOnlyBlocksNodesThatDependOnIt() {
        Node independent = new Node("independent", List.of(), Status.PENDING);
        Node blocked = new Node("blocked", List.of("missing"), Status.PENDING);
        List<Node> nodes = List.of(independent, blocked);

        DependencyGraph<Node> graph = DependencyGraph.of(nodes, Node::id, Node::dependencies);

        assertFalse(graph.isValid());
        assertEquals(List.of(independent),
                graph.readyNodes(node -> node.status() == Status.PENDING,
                        depId -> nodes.stream()
                                .filter(node -> node.id().equals(depId))
                                .map(Node::status)
                                .anyMatch(status -> status == Status.COMPLETED || status == Status.SKIPPED)));
    }

    @Test
    void invalidGraphsReportDuplicateMissingAndCycleIssues() {
        DependencyGraph<Node> duplicate = DependencyGraph.of(
                List.of(
                        new Node("same", List.of(), Status.PENDING),
                        new Node("same", List.of(), Status.PENDING)),
                Node::id,
                Node::dependencies);

        assertFalse(duplicate.isValid());
        assertTrue(duplicate.issues().stream().anyMatch(issue -> issue.contains("重复")));

        DependencyGraph<Node> missing = DependencyGraph.of(
                List.of(new Node("root", List.of("missing"), Status.PENDING)),
                Node::id,
                Node::dependencies);

        assertFalse(missing.isValid());
        assertTrue(missing.issues().stream().anyMatch(issue -> issue.contains("不存在")));

        DependencyGraph<Node> cycle = DependencyGraph.of(
                List.of(
                        new Node("a", List.of("b"), Status.PENDING),
                        new Node("b", List.of("a"), Status.PENDING)),
                Node::id,
                Node::dependencies);

        assertFalse(cycle.isValid());
        assertTrue(cycle.issues().stream().anyMatch(issue -> issue.contains("循环")));
    }

    @Test
    void blockedNodesReportMissingAndUnfinishedDependencies() {
        Node completed = new Node("completed", List.of(), Status.COMPLETED);
        Node failed = new Node("failed", List.of(), Status.FAILED);
        Node waiting = new Node("waiting", List.of("completed", "failed", "missing"), Status.PENDING);
        List<Node> nodes = List.of(completed, failed, waiting);

        DependencyGraph<Node> graph = DependencyGraph.of(nodes, Node::id, Node::dependencies);
        var blocked = graph.blockedNodes(
                node -> node.status() == Status.PENDING,
                depId -> nodes.stream()
                        .filter(node -> node.id().equals(depId))
                        .map(Node::status)
                        .findFirst()
                        .map(Enum::name)
                        .orElse("MISSING"),
                state -> state.equals("COMPLETED") || state.equals("SKIPPED"));

        assertEquals(1, blocked.size());
        assertEquals("waiting", blocked.get(0).node().id());
        assertEquals(List.of("failed=FAILED", "missing=MISSING"),
                blocked.get(0).blockingDependencies().stream()
                        .map(dep -> dep.dependencyId() + "=" + dep.state())
                        .toList());
    }

    @Test
    void blockedTasksExposePlanLevelReasons() {
        ExecutionPlan plan = new ExecutionPlan("plan", "goal");
        Task first = new Task("task_1", "done", Task.TaskType.COMMAND);
        Task second = new Task("task_2", "waiting", Task.TaskType.ANALYSIS, List.of("task_1", "task_3"));
        plan.addTask(first);
        plan.addTask(second);
        first.markCompleted("ok");

        List<DependencyGraph.BlockedNode<Task>> blocked = plan.getBlockedTasks();

        assertEquals(1, blocked.size());
        assertEquals("task_2", blocked.get(0).node().getId());
        assertEquals(List.of("task_3=MISSING"),
                blocked.get(0).blockingDependencies().stream()
                        .map(dep -> dep.dependencyId() + "=" + dep.state())
                        .toList());
    }
}
