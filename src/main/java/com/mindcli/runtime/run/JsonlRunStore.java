package com.mindcli.runtime.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

public final class JsonlRunStore implements RunStore {
    private static final ObjectMapper MAPPER = com.mindcli.platform.serialization.JsonSupport.mapper();
    private final Path runsRoot;
    private final RunStateProjector projector = new RunStateProjector();

    public JsonlRunStore(Path runsRoot) {
        this.runsRoot = Objects.requireNonNull(runsRoot, "runsRoot");
    }

    @Override
    public synchronized void append(AgentRunEvent event) {
        Objects.requireNonNull(event, "event");
        Path ledgerFile = ledgerFile(event);
        try {
            Files.createDirectories(ledgerFile.getParent());
            LoadedLedger loaded = loadLedger(ledgerFile);
            AgentRunEvent persistedEvent = event.seq() > 0 ? event : event.withSeq(loaded.nextSeq());
            repairCorruptedTail(ledgerFile, loaded);
            Files.writeString(ledgerFile, MAPPER.writeValueAsString(toRecord(persistedEvent)) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            List<AgentRunEvent> updatedEvents = new ArrayList<>(loaded.events());
            updatedEvents.add(persistedEvent);
            refreshDerivedFiles(persistedEvent.runId(), ledgerFile.getParent(), updatedEvents);
            String parentRunId = persistedEvent.attributes().get("parentRunId");
            if (parentRunId != null && !parentRunId.isBlank()) {
                refreshDerivedFiles(parentRunId);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to append run event: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized List<AgentRunEvent> events(String runId) {
        if (runId == null || runId.isBlank()) {
            return List.of();
        }
        Path ledgerFile = ledgerFile(runId);
        if (!Files.exists(ledgerFile)) {
            return List.of();
        }
        try {
            return loadLedger(ledgerFile).events();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read run events: " + e.getMessage(), e);
        }
    }

    public Path runsRoot() {
        return runsRoot;
    }

    public Path runDir(String runId) {
        return findRunDir(runId);
    }

    public Path ledgerPath(String runId) {
        return runDir(runId).resolve("run.jsonl");
    }

    public Path metaPath(String runId) {
        return runDir(runId).resolve("run.meta.json");
    }

    public Path statePath(String runId) {
        return runDir(runId).resolve("run.state.json");
    }

    public Path artifactsDir(String runId) {
        return runDir(runId).resolve("artifacts");
    }

    public Path childrenDir(String runId) {
        return runDir(runId).resolve("children");
    }

    private Path ledgerFile(String runId) {
        return ledgerPath(runId);
    }

    private Path ledgerFile(AgentRunEvent event) {
        return runDir(event).resolve("run.jsonl");
    }

    private Path runDir(AgentRunEvent event) {
        String parentRunId = event.attributes().get("parentRunId");
        if (parentRunId != null && !parentRunId.isBlank() && !parentRunId.equals(event.runId())) {
            return childrenDir(parentRunId).resolve(event.runId());
        }
        return runsRoot.resolve(event.runId());
    }

    private static Map<String, Object> toRecord(AgentRunEvent event) {
        return Map.of(
                "runId", event.runId(),
                "eventId", event.eventId(),
                "seq", event.seq(),
                "type", event.type().name(),
                "timestamp", event.timestamp().toString(),
                "attributes", event.attributes());
    }

    private static AgentRunEvent fromRecord(JsonNode node) {
        String runId = node.path("runId").asText("");
        String eventId = node.path("eventId").asText("");
        long seq = node.path("seq").asLong(0L);
        AgentRunEventType type = AgentRunEventType.valueOf(node.path("type").asText("RUN_FAILED"));
        String timestamp = node.path("timestamp").asText("");
        JsonNode attrsNode = node.path("attributes");
        Map<String, String> attributes = MAPPER.convertValue(attrsNode, MAPPER.getTypeFactory()
                .constructMapType(Map.class, String.class, String.class));
        return new AgentRunEvent(
                runId,
                type,
                timestamp == null || timestamp.isBlank() ? null : java.time.Instant.parse(timestamp),
                eventId,
                seq,
                attributes);
    }

    private void refreshDerivedFiles(String runId) throws IOException {
        Path runDir = runDir(runId);
        refreshDerivedFiles(runId, runDir, loadLedger(runDir.resolve("run.jsonl")).events());
    }

    private void refreshDerivedFiles(String runId, Path runDir, List<AgentRunEvent> events) throws IOException {
        RunStateProjection projection = projector.project(events);
        Files.createDirectories(runDir);
        Files.createDirectories(runDir.resolve("artifacts"));
        Files.createDirectories(runDir.resolve("children"));

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("runId", runId);
        meta.put("mode", events.isEmpty() ? "" : events.get(0).attributes().getOrDefault("mode", ""));
        meta.put("workspace", events.isEmpty() ? "" : events.get(0).attributes().getOrDefault("workspace", ""));
        meta.put("startedAt", events.isEmpty() ? Instant.now().toString() : events.get(0).timestamp().toString());
        meta.put("eventCount", events.size());
        if (!events.isEmpty()) {
            String parentRunId = events.get(0).attributes().get("parentRunId");
            String rootRunId = events.get(0).attributes().get("rootRunId");
            if (parentRunId != null && !parentRunId.isBlank()) {
                meta.put("parentRunId", parentRunId);
            }
            if (rootRunId != null && !rootRunId.isBlank()) {
                meta.put("rootRunId", rootRunId);
            }
        }
        MAPPER.writeValue(runDir.resolve("run.meta.json").toFile(), meta);

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("runId", runId);
        state.put("status", projection.status().name());
        state.put("lastEventType", projection.lastEventType() == null ? "" : projection.lastEventType().name());
        state.put("lastCompletedEventType", projection.lastCompletedEventType() == null ? "" : projection.lastCompletedEventType().name());
        state.put("lastCompletedAttributes", projection.lastCompletedAttributes());
        state.put("lastEventAttributes", projection.lastEventAttributes());
        state.put("childRuns", childRunSummaries(runId));
        state.put("eventCount", projection.events().size());
        MAPPER.writeValue(runDir.resolve("run.state.json").toFile(), state);
    }

    private Path findRunDir(String runId) {
        Path rootCandidate = runsRoot.resolve(runId);
        if (Files.exists(rootCandidate)) {
            return rootCandidate;
        }
        if (!Files.exists(runsRoot)) {
            return rootCandidate;
        }
        try (Stream<Path> paths = Files.walk(runsRoot, 3)) {
            return paths
                    .filter(Files::isDirectory)
                    .filter(path -> runId.equals(path.getFileName().toString()))
                    .findFirst()
                    .orElse(rootCandidate);
        } catch (IOException e) {
            return rootCandidate;
        }
    }

    static LoadedLedger loadLedger(Path ledgerFile) throws IOException {
        if (!Files.exists(ledgerFile)) {
            return LoadedLedger.empty();
        }
        List<AgentRunEvent> events = new ArrayList<>();
        boolean corruptedTail = false;
        for (String line : Files.readAllLines(ledgerFile, StandardCharsets.UTF_8)) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                JsonNode node = MAPPER.readTree(trimmed);
                AgentRunEvent event = fromRecord(node);
                events.add(event.seq() > 0 ? event : event.withSeq(events.size() + 1L));
            } catch (Exception ignored) {
                corruptedTail = true;
                break;
            }
        }
        return new LoadedLedger(events, corruptedTail);
    }

    private void repairCorruptedTail(Path ledgerFile, LoadedLedger loaded) throws IOException {
        if (!loaded.corruptedTail()) {
            return;
        }
        StringBuilder normalized = new StringBuilder();
        for (AgentRunEvent event : loaded.events()) {
            normalized.append(MAPPER.writeValueAsString(toRecord(event)))
                    .append(System.lineSeparator());
        }
        Files.writeString(ledgerFile, normalized.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private List<Map<String, Object>> childRunSummaries(String runId) throws IOException {
        Path childrenDir = childrenDir(runId);
        if (!Files.isDirectory(childrenDir)) {
            return List.of();
        }
        List<Map<String, Object>> summaries = new ArrayList<>();
        try (Stream<Path> paths = Files.list(childrenDir)) {
            for (Path childDir : paths.filter(Files::isDirectory).sorted().toList()) {
                Path ledgerFile = childDir.resolve("run.jsonl");
                if (!Files.exists(ledgerFile)) {
                    continue;
                }
                List<AgentRunEvent> childEvents = loadLedger(ledgerFile).events();
                RunStateProjection projection = projector.project(childEvents);
                Map<String, String> attributes = childEvents.isEmpty()
                        ? Map.of()
                        : childEvents.get(0).attributes();
                Map<String, String> lastAttributes = projection.lastEventAttributes();
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("runId", childDir.getFileName().toString());
                summary.put("role", attributes.getOrDefault("role", ""));
                summary.put("stepId", attributes.getOrDefault("stepId", ""));
                summary.put("attempt", attributes.getOrDefault("attempt", ""));
                summary.put("profileName", attributes.getOrDefault("profileName", ""));
                summary.put("profileRole", attributes.getOrDefault("profileRole", ""));
                summary.put("permissionMode", attributes.getOrDefault("permissionMode", ""));
                summary.put("selectedReason", attributes.getOrDefault("selectedReason", ""));
                summary.put("status", projection.status().name());
                summary.put("businessStatus", lastAttributes.getOrDefault("businessStatus",
                        lastAttributes.getOrDefault("status", "")));
                summary.put("approved", lastAttributes.getOrDefault("approved", ""));
                summary.put("lastEventType", projection.lastEventType() == null ? "" : projection.lastEventType().name());
                summary.put("lastEventAttributes", lastAttributes);
                summaries.add(summary);
            }
        }
        return List.copyOf(summaries);
    }

    record LoadedLedger(List<AgentRunEvent> events, boolean corruptedTail) {
        LoadedLedger {
            events = events == null ? List.of() : List.copyOf(events);
        }

        static LoadedLedger empty() {
            return new LoadedLedger(List.of(), false);
        }

        long nextSeq() {
            return events.stream()
                    .mapToLong(AgentRunEvent::seq)
                    .max()
                    .orElse(0L) + 1L;
        }
    }
}
