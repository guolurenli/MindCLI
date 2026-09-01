package com.mindcli.capability.memory;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class JsonlMemoryProposalStore implements MemoryProposalStore {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private final Map<String, MemoryProposal> proposals = new LinkedHashMap<>();

    public JsonlMemoryProposalStore(Path file) {
        this.file = file;
        load();
    }

    @Override
    public synchronized void save(MemoryProposal proposal) {
        if (proposal == null) {
            return;
        }
        append(proposal);
        proposals.put(proposal.id(), proposal);
    }

    @Override
    public synchronized List<MemoryProposal> list() {
        return List.copyOf(proposals.values());
    }

    @Override
    public synchronized Optional<MemoryProposal> findById(String id) {
        return Optional.ofNullable(proposals.get(id));
    }

    @Override
    public synchronized boolean updateStatus(String id, MemoryProposal.Status status) {
        MemoryProposal existing = proposals.get(id);
        if (existing == null) {
            return false;
        }
        save(existing.withStatus(status));
        return true;
    }

    @Override
    public Optional<Path> storagePath() {
        return Optional.of(file);
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                MemoryProposal proposal = fromRecord(MAPPER.readValue(line, ProposalRecord.class));
                proposals.put(proposal.id(), proposal);
            }
        } catch (IOException e) {
            // Corrupt proposal history should not block CLI startup.
        }
    }

    private void append(MemoryProposal proposal) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, MAPPER.writeValueAsString(toRecord(proposal)) + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("写入候选记忆失败: " + e.getMessage(), e);
        }
    }

    private static ProposalRecord toRecord(MemoryProposal proposal) {
        return new ProposalRecord(
                proposal.id(),
                proposal.name(),
                proposal.content(),
                proposal.type().name(),
                proposal.metadata(),
                proposal.createdAt().toString(),
                proposal.status().name()
        );
    }

    private static MemoryProposal fromRecord(ProposalRecord record) {
        MemoryEntry.MemoryType type;
        try {
            type = MemoryEntry.MemoryType.valueOf(record.type);
        } catch (Exception e) {
            type = MemoryEntry.MemoryType.PROJECT_FACT;
        }
        MemoryProposal.Status status;
        try {
            status = MemoryProposal.Status.valueOf(record.status);
        } catch (Exception e) {
            status = MemoryProposal.Status.PROPOSED;
        }
        Instant createdAt;
        try {
            createdAt = Instant.parse(record.createdAt);
        } catch (Exception e) {
            createdAt = Instant.now();
        }
        return new MemoryProposal(
                record.id,
                record.name,
                record.content,
                type,
                record.metadata == null ? Map.of() : Map.copyOf(record.metadata),
                createdAt,
                status
        );
    }

    @SuppressWarnings("unused")
    private static final class ProposalRecord {
        public String id;
        public String name;
        public String content;
        public String type;
        public Map<String, String> metadata;
        public String createdAt;
        public String status;

        public ProposalRecord() {
        }

        ProposalRecord(String id, String name, String content, String type, Map<String, String> metadata,
                       String createdAt, String status) {
            this.id = id;
            this.name = name;
            this.content = content;
            this.type = type;
            this.metadata = metadata;
            this.createdAt = createdAt;
            this.status = status;
        }
    }
}
