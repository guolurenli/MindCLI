package com.mindcli.capability.memory;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface MemoryProposalStore {
    void save(MemoryProposal proposal);

    List<MemoryProposal> list();

    Optional<MemoryProposal> findById(String id);

    boolean updateStatus(String id, MemoryProposal.Status status);

    Optional<Path> storagePath();
}
