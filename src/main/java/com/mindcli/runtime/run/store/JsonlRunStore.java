package com.mindcli.runtime.run.store;
import com.mindcli.runtime.run.*;
import com.mindcli.runtime.run.dispatch.*;
import com.mindcli.runtime.run.hook.*;
import com.mindcli.runtime.run.legacy.*;
import com.mindcli.runtime.run.loop.*;
import com.mindcli.runtime.run.mode.*;
import com.mindcli.runtime.run.recovery.*;
import com.mindcli.runtime.run.session.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

public final class JsonlRunStore implements RunStore {
    private static final ObjectMapper MAPPER = com.mindcli.platform.serialization.JsonSupport.mapper();
    private static final ConcurrentHashMap<Path, ReentrantLock> JVM_LEDGER_LOCKS = new ConcurrentHashMap<>();
    private final Path runsRoot;
    private final RunStateProjector projector = new RunStateProjector();

    public JsonlRunStore(Path runsRoot) {
        this.runsRoot = Objects.requireNonNull(runsRoot, "runsRoot");
    }

    @Override
    public void append(AgentRunEvent event) {
        Objects.requireNonNull(event, "event");
        Path ledgerFile = ledgerFile(event);
        try {
            Files.createDirectories(ledgerFile.getParent());
            AgentRunEvent persistedEvent = withLedgerLock(ledgerFile, () -> {
                LoadedLedger loaded = loadLedger(ledgerFile);
                AgentRunEvent nextEvent = event.seq() > 0 ? event : event.withSeq(loaded.nextSeq());
                repairCorruptedTail(ledgerFile, loaded);
                Files.writeString(ledgerFile, MAPPER.writeValueAsString(toRecord(nextEvent)) + System.lineSeparator(),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                List<AgentRunEvent> updatedEvents = new ArrayList<>(loaded.events());
                updatedEvents.add(nextEvent);
                refreshDerivedFiles(nextEvent.runId(), ledgerFile.getParent(), updatedEvents);
                return nextEvent;
            });
            String parentRunId = persistedEvent.attributes().get("parentRunId");
            if (parentRunId != null && !parentRunId.isBlank()) {
                refreshDerivedFiles(parentRunId);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to append run event: " + e.getMessage(), e);
        }
    }

    @Override
    public List<AgentRunEvent> events(String runId) {
        if (runId == null || runId.isBlank()) {
            return List.of();
        }
        requireSafeRunId(runId, "runId");
        Path ledgerFile = ledgerFile(runId);
        if (!Files.exists(ledgerFile)) {
            return List.of();
        }
        try {
            return withLedgerLock(ledgerFile, () -> loadLedger(ledgerFile).events());
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

    /** Returns only persisted parent runs directly below the configured runs root. */
    public List<String> topLevelRunIds() {
        if (!Files.isDirectory(runsRoot)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(runsRoot)) {
            return paths
                    .filter(Files::isDirectory)
                    .filter(path -> Files.isRegularFile(path.resolve("run.jsonl")))
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException | SecurityException ignored) {
            return List.of();
        }
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
            requireSafeRunId(parentRunId, "parentRunId");
            return ensureWithinRunsRoot(childrenDir(parentRunId).resolve(event.runId()));
        }
        return ensureWithinRunsRoot(runsRoot.resolve(event.runId()));
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
        Path ledgerFile = runDir.resolve("run.jsonl");
        withLedgerLock(ledgerFile, () -> {
            refreshDerivedFiles(runId, runDir, loadLedger(ledgerFile).events());
            return null;
        });
    }

    private <T> T withLedgerLock(Path ledgerFile, LedgerOperation<T> operation) throws IOException {
        Path lockPath = realPathForPotentiallyMissing(ledgerFile.toAbsolutePath().normalize()
                .resolveSibling(ledgerFile.getFileName() + ".lock"));
        ReentrantLock jvmLock = JVM_LEDGER_LOCKS.computeIfAbsent(lockPath, ignored -> new ReentrantLock());
        jvmLock.lock();
        try {
            Files.createDirectories(lockPath.getParent());
            try (FileChannel channel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = acquireFileLock(channel)) {
                return operation.run();
            }
        } finally {
            jvmLock.unlock();
        }
    }

    private static FileLock acquireFileLock(FileChannel channel) throws IOException {
        while (true) {
            try {
                return channel.lock();
            } catch (OverlappingFileLockException e) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Interrupted while waiting for ledger lock", e);
                }
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for ledger lock", interrupted);
                }
            }
        }
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
        requireSafeRunId(runId, "runId");
        Path rootCandidate = runsRoot.resolve(runId);
        if (Files.exists(rootCandidate)) {
            return ensureWithinRunsRoot(rootCandidate);
        }
        if (!Files.exists(runsRoot)) {
            return ensureWithinRunsRoot(rootCandidate);
        }
        try (Stream<Path> paths = Files.walk(runsRoot, 3)) {
            return paths
                    .filter(Files::isDirectory)
                    .filter(path -> runId.equals(path.getFileName().toString()))
                    .findFirst()
                    .map(this::ensureWithinRunsRoot)
                    .orElseGet(() -> ensureWithinRunsRoot(rootCandidate));
        } catch (IOException e) {
            return ensureWithinRunsRoot(rootCandidate);
        }
    }

    private static void requireSafeRunId(String runId, String field) {
        if (runId == null || runId.isBlank()
                || !runId.matches("[A-Za-z0-9][A-Za-z0-9._-]*")
                || runId.contains("..")
                || runId.contains("/")
                || runId.contains("\\")) {
            throw new IllegalArgumentException(field + " contains unsafe path characters: " + runId);
        }
    }

    /**
     * Resolves existing symlink components before checking containment. A safe-looking
     * run id must not be able to escape the configured runs root through a symlink.
     */
    private Path ensureWithinRunsRoot(Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        Path root = runsRoot.toAbsolutePath().normalize();
        Path realRoot = realPathForPotentiallyMissing(root);
        Path existing = normalized;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        Path resolved = normalized;
        if (existing != null) {
            Path realExisting = realPathIfExists(existing);
            resolved = realExisting.resolve(existing.relativize(normalized)).normalize();
        }
        if (!resolved.startsWith(realRoot)) {
            throw new IllegalArgumentException("path escapes runs root: " + candidate);
        }
        return normalized;
    }

    private static Path realPathIfExists(Path path) {
        try {
            return Files.exists(path) ? path.toRealPath() : path;
        } catch (IOException e) {
            return path;
        }
    }

    private static Path realPathForPotentiallyMissing(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        Path existing = normalized;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return normalized;
        }
        Path realExisting = realPathIfExists(existing);
        return realExisting.resolve(existing.relativize(normalized)).normalize();
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
                List<AgentRunEvent> childEvents = withLedgerLock(ledgerFile,
                        () -> loadLedger(ledgerFile).events());
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

    @FunctionalInterface
    private interface LedgerOperation<T> {
        T run() throws IOException;
    }
}
