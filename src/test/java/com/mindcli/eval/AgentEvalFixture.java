package com.mindcli.eval;

import com.mindcli.agent.Agent;
import com.mindcli.agent.PlanExecuteAgent;
import com.mindcli.agent.team.AgentOrchestrator;
import com.mindcli.capability.memory.LongTermMemory;
import com.mindcli.capability.memory.MemoryManager;
import com.mindcli.capability.tool.ToolExecution;
import com.mindcli.capability.tool.ToolOutput;
import com.mindcli.capability.tool.ToolRegistry;
import com.mindcli.platform.llm.LlmClient;
import com.mindcli.platform.serialization.JsonSupport;
import com.mindcli.runtime.run.AgentMode;
import com.mindcli.runtime.run.AgentRunContext;
import com.mindcli.runtime.run.AgentRunEvent;
import com.mindcli.runtime.run.AgentRunEventType;
import com.mindcli.runtime.run.AgentRunResult;
import com.mindcli.runtime.run.AgentRuntime;
import com.mindcli.runtime.run.dispatch.ToolOutcomeStatus;
import com.mindcli.runtime.run.mode.PlanModeAdapter;
import com.mindcli.runtime.run.mode.ReActModeAdapter;
import com.mindcli.runtime.run.mode.TeamModeAdapter;
import com.mindcli.runtime.run.store.InMemoryRunStore;
import com.mindcli.runtime.run.store.RunStore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;
import java.util.stream.Stream;

final class AgentEvalFixture {
    private final Path workspace;

    private AgentEvalFixture(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
    }

    static AgentEvalFixture workspace(Path root, Map<String, String> files) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Files.createDirectories(normalizedRoot);
        for (Map.Entry<String, String> entry : files.entrySet()) {
            Path target = normalizedRoot.resolve(entry.getKey()).normalize();
            if (!target.startsWith(normalizedRoot)) {
                throw new IllegalArgumentException("Fixture file escapes workspace: " + entry.getKey());
            }
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, entry.getValue(), StandardCharsets.UTF_8);
        }
        return new AgentEvalFixture(normalizedRoot);
    }

    AgentEvalResult runReact(ScriptedLlmClient llm, String prompt) {
        EvalRunStore store = new EvalRunStore();
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(workspace.toString());
        Agent agent = new Agent(llm, registry, store);
        AgentRunContext context = AgentRunContext.create(AgentMode.REACT, prompt, workspace.toString());
        AgentRunResult result = new AgentRuntime(store).run(context, new ReActModeAdapter(agent));
        return new AgentEvalResult(workspace, result, store.events(context.runId()), store.allEvents());
    }

    AgentEvalResult runPlan(ScriptedLlmClient llm, String prompt) {
        EvalRunStore store = new EvalRunStore();
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(workspace.toString());
        MemoryManager memoryManager = memoryManager(llm);
        PlanExecuteAgent agent = new PlanExecuteAgent(
                llm,
                registry,
                memoryManager,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                store);
        AgentRunContext context = AgentRunContext.create(AgentMode.PLAN, prompt, workspace.toString());
        AgentRunResult result = new AgentRuntime(store).run(context, new PlanModeAdapter(agent));
        return new AgentEvalResult(workspace, result, store.events(context.runId()), store.allEvents());
    }

    AgentEvalResult runTeam(ScriptedLlmClient llm, String prompt) {
        EvalRunStore store = new EvalRunStore();
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(workspace.toString());
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                llm,
                registry,
                memoryManager(llm),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                store);
        AgentRunContext context = AgentRunContext.create(AgentMode.TEAM, prompt, workspace.toString());
        AgentRunResult result = new AgentRuntime(store).run(context, new TeamModeAdapter(orchestrator));
        return new AgentEvalResult(workspace, result, store.events(context.runId()), store.allEvents());
    }

    private MemoryManager memoryManager(LlmClient llm) {
        return new MemoryManager(
                llm,
                llm.maxContextWindow(),
                new LongTermMemory(workspace.resolve(".eval-memory").toFile()));
    }

    static LlmClient.ChatResponse response(String content) {
        return new LlmClient.ChatResponse("assistant", content, null, 10, 5);
    }

    static LlmClient.ChatResponse toolResponse(String content, String id, String name,
                                               String argumentsJson) {
        LlmClient.ToolCall toolCall = new LlmClient.ToolCall(
                id,
                new LlmClient.ToolCall.Function(name, argumentsJson));
        return new LlmClient.ChatResponse("assistant", content, List.of(toolCall), 10, 5);
    }

    static String writeArgs(Path path, String content) throws IOException {
        return JsonSupport.mapper().writeValueAsString(Map.of(
                "path", path.toString(),
                "content", content));
    }

    static AgentRunContext resumedContext(String runId, Path workspace) {
        return new AgentRunContext(
                runId,
                AgentMode.REACT,
                "resume",
                workspace.toAbsolutePath().normalize().toString(),
                Instant.now(),
                Map.of("resumed", "true"));
    }

    static void appendCompletedOutcome(InMemoryRunStore store,
                                       AgentRunContext context,
                                       String toolId,
                                       String toolName,
                                       String argumentsJson,
                                       String text) {
        store.append(AgentRunEvent.of(context, AgentRunEventType.TOOL_OUTCOME, Map.of(
                "toolId", toolId,
                "toolName", toolName,
                "argumentsJson", argumentsJson,
                "status", ToolOutcomeStatus.COMPLETED.name(),
                "text", text)));
    }

    static LlmClient.ToolCall toolCall(String id, String name, String argumentsJson) {
        return new LlmClient.ToolCall(id, new LlmClient.ToolCall.Function(name, argumentsJson));
    }

    static ToolExecution completed(ToolRegistry.ToolInvocation invocation, String text) {
        return ToolExecution.completed(ToolOutput.text(text), invocation.argumentsJson());
    }

    Map<String, String> snapshotFiles() throws IOException {
        return snapshotFiles(workspace);
    }

    private static Map<String, String> snapshotFiles(Path workspace) throws IOException {
        Map<String, String> snapshot = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(workspace)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String relative = workspace.relativize(path).toString().replace('\\', '/');
                snapshot.put(relative, Files.readString(path, StandardCharsets.UTF_8));
            }
        }
        return Collections.unmodifiableMap(snapshot);
    }

    record AgentEvalResult(Path workspace,
                           AgentRunResult runResult,
                           List<AgentRunEvent> events,
                           List<AgentRunEvent> allEvents) {
        String read(String relativePath) throws IOException {
            return Files.readString(workspace.resolve(relativePath), StandardCharsets.UTF_8);
        }

        Map<String, String> snapshotFiles() throws IOException {
            return AgentEvalFixture.snapshotFiles(workspace);
        }

        long successfulToolCalls(String toolName) {
            return toolOutcomes(toolName).stream()
                    .filter(event -> "COMPLETED".equals(event.attributes().get("status")))
                    .count();
        }

        List<AgentRunEvent> toolOutcomes(String toolName) {
            return events.stream()
                    .filter(event -> event.type() == AgentRunEventType.TOOL_OUTCOME)
                    .filter(event -> toolName.equals(event.attributes().get("toolName")))
                    .toList();
        }

        AgentRunEvent toolOutcome(String toolId) {
            return events.stream()
                    .filter(event -> event.type() == AgentRunEventType.TOOL_OUTCOME)
                    .filter(event -> toolId.equals(event.attributes().get("toolId")))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Missing TOOL_OUTCOME for " + toolId));
        }

        int firstToolOutcomeIndex(String toolId) {
            for (int i = 0; i < events.size(); i++) {
                AgentRunEvent event = events.get(i);
                if (event.type() == AgentRunEventType.TOOL_OUTCOME
                        && toolId.equals(event.attributes().get("toolId"))) {
                    return i;
                }
            }
            throw new AssertionError("Missing TOOL_OUTCOME for " + toolId);
        }
    }

    static final class ScriptedLlmClient implements LlmClient {
        private final Queue<Object> steps;

        private ScriptedLlmClient(List<?> steps) {
            this.steps = new ArrayDeque<>(steps);
        }

        static ScriptedLlmClient sequence(ChatResponse... responses) {
            return new ScriptedLlmClient(List.of(responses));
        }

        static ScriptedLlmClient steps(Object... steps) {
            return new ScriptedLlmClient(List.of(steps));
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public synchronized ChatResponse chat(List<Message> messages, List<Tool> tools,
                                              StreamListener listener) throws IOException {
            Object step = steps.poll();
            if (step == null) {
                throw new IOException("No scripted response for: " + lastUserMessage(messages));
            }
            if (step instanceof IOException failure) {
                throw failure;
            }
            if (!(step instanceof ChatResponse response)) {
                throw new IOException("Unsupported scripted step: " + step.getClass().getName());
            }
            if (response.reasoningContent() != null && !response.reasoningContent().isEmpty()) {
                listener.onReasoningDelta(response.reasoningContent());
            }
            if (response.content() != null && !response.content().isEmpty()) {
                listener.onContentDelta(response.content());
            }
            return response;
        }

        @Override
        public String getModelName() {
            return "eval-scripted";
        }

        @Override
        public String getProviderName() {
            return "eval";
        }

        private static String lastUserMessage(List<Message> messages) {
            if (messages == null) {
                return "";
            }
            for (int i = messages.size() - 1; i >= 0; i--) {
                Message message = messages.get(i);
                if ("user".equals(message.role())) {
                    return message.content() == null ? "" : message.content();
                }
            }
            return "";
        }
    }

    private static final class EvalRunStore implements RunStore {
        private final List<AgentRunEvent> events = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void append(AgentRunEvent event) {
            synchronized (events) {
                long seq = event.seq() > 0 ? event.seq() : events.stream()
                        .filter(existing -> existing.runId().equals(event.runId()))
                        .count() + 1;
                events.add(event.withSeq(seq));
            }
        }

        @Override
        public List<AgentRunEvent> events(String runId) {
            synchronized (events) {
                return events.stream()
                        .filter(event -> event.runId().equals(runId))
                        .toList();
            }
        }

        List<AgentRunEvent> allEvents() {
            synchronized (events) {
                return List.copyOf(events);
            }
        }
    }
}
