package com.mindcli.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryProposalStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsAndReloadsProposals() {
        JsonlMemoryProposalStore store = new JsonlMemoryProposalStore(tempDir.resolve("proposals.jsonl"));
        MemoryProposal proposal = MemoryProposal.proposed(
                "用户偏好",
                "用户偏好使用中文回答",
                MemoryEntry.MemoryType.USER_PREFERENCE,
                Map.of("scope", "project", "source", "extractor"));

        store.save(proposal);

        JsonlMemoryProposalStore reloaded = new JsonlMemoryProposalStore(tempDir.resolve("proposals.jsonl"));
        List<MemoryProposal> proposals = reloaded.list();

        assertEquals(1, proposals.size());
        assertEquals(proposal.id(), proposals.get(0).id());
        assertEquals(MemoryProposal.Status.PROPOSED, proposals.get(0).status());
        assertNotNull(reloaded.storagePath().orElse(null));
        assertTrue(reloaded.storagePath().get().toString().endsWith("proposals.jsonl"));
    }

    @Test
    void updatesProposalStatusWithoutDuplicateActiveEntries() {
        JsonlMemoryProposalStore store = new JsonlMemoryProposalStore(tempDir.resolve("proposals.jsonl"));
        MemoryProposal proposal = MemoryProposal.proposed(
                "项目事实",
                "当前项目使用 Java 17",
                MemoryEntry.MemoryType.PROJECT_FACT,
                Map.of("scope", "project"));

        store.save(proposal);
        store.updateStatus(proposal.id(), MemoryProposal.Status.APPROVED);

        List<MemoryProposal> proposals = store.list();

        assertEquals(1, proposals.size());
        assertEquals(MemoryProposal.Status.APPROVED, proposals.get(0).status());
    }
}
