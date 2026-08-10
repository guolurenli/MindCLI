package com.mindcli.agent.profile;

import com.mindcli.agent.AgentRole;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

public final class AgentPool {
    private final List<AgentProfile> profiles;
    private final Map<String, Semaphore> leases = new ConcurrentHashMap<>();

    public AgentPool(List<AgentProfile> profiles) {
        this.profiles = profiles == null ? List.of() : List.copyOf(profiles);
        for (AgentProfile profile : this.profiles) {
            leases.put(profile.name(), new Semaphore(profile.maxConcurrency()));
        }
    }

    public AgentLease acquire(AgentRole role, AgentTaskRequirements requirements) {
        AgentTaskRequirements req = requirements == null
                ? new AgentTaskRequirements("", List.of(), "", "low")
                : requirements;
        if (!req.preferredAgent().isBlank()) {
            AgentProfile preferred = profiles.stream()
                    .filter(profile -> profile.role() == role)
                    .filter(profile -> profile.name().equals(req.preferredAgent()))
                    .filter(profile -> satisfies(profile, req.requiredTools()))
                    .findFirst()
                    .orElse(null);
            if (preferred != null) {
                acquireBlocking(preferred);
                return new AgentLease(preferred, "preferredAgent matched", this);
            }
        }

        List<AgentProfile> candidates = profiles.stream()
                .filter(profile -> profile.role() == role)
                .filter(profile -> satisfies(profile, req.requiredTools()))
                .sorted(Comparator.comparingInt(AgentProfile::privilegeScore)
                        .thenComparing(AgentProfile::name))
                .toList();
        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                    "No " + role + " profile satisfies required tools: " + req.requiredTools());
        }
        AgentProfile selected = candidates.stream()
                .filter(this::hasAvailableLease)
                .findFirst()
                .orElse(candidates.get(0));
        acquireBlocking(selected);
        return new AgentLease(selected, "requiredTools matched", this);
    }

    public List<AgentProfile> profiles(AgentRole role) {
        return profiles.stream().filter(profile -> profile.role() == role).toList();
    }

    private boolean satisfies(AgentProfile profile, List<String> requiredTools) {
        if (requiredTools == null || requiredTools.isEmpty()) {
            return true;
        }
        for (String tool : requiredTools) {
            if (!profile.allowsTool(tool)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasAvailableLease(AgentProfile profile) {
        Semaphore semaphore = leases.get(profile.name());
        return semaphore != null && semaphore.availablePermits() > 0;
    }

    private void acquireBlocking(AgentProfile profile) {
        Semaphore semaphore = leases.get(profile.name());
        if (semaphore == null) {
            throw new IllegalStateException("No lease configured for profile: " + profile.name());
        }
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for profile lease: " + profile.name(), e);
        }
    }

    private void release(AgentProfile profile) {
        Semaphore semaphore = leases.get(profile.name());
        if (semaphore != null) {
            semaphore.release();
        }
    }

    public static final class AgentLease implements AutoCloseable {
        private final AgentProfile profile;
        private final String selectionReason;
        private final AgentPool owner;
        private boolean closed;

        private AgentLease(AgentProfile profile, String selectionReason, AgentPool owner) {
            this.profile = profile;
            this.selectionReason = selectionReason;
            this.owner = owner;
        }

        public AgentProfile profile() {
            return profile;
        }

        public String selectionReason() {
            return selectionReason;
        }

        @Override
        public void close() {
            if (!closed) {
                owner.release(profile);
                closed = true;
            }
        }
    }
}
