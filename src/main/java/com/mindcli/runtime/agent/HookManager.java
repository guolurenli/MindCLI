package com.mindcli.runtime.agent;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

public final class HookManager {
    private static final HookManager NOOP = new HookManager(List.of());

    private final List<Handler> handlers;

    public HookManager(Collection<Handler> handlers) {
        this.handlers = handlers == null ? List.of() : List.copyOf(handlers);
    }

    public static HookManager noop() {
        return NOOP;
    }

    public HookDecision fire(HookEvent event) {
        Objects.requireNonNull(event, "event");
        for (Handler handler : handlers) {
            HookDecision decision;
            try {
                decision = handler.handle(event);
            } catch (Exception e) {
                return HookDecision.denyByPolicy("Hook failed: " + errorMessage(e));
            }
            if (decision != null && decision.type() != HookDecisionType.ALLOW) {
                return decision;
            }
        }
        return HookDecision.allow();
    }

    private static String errorMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    @FunctionalInterface
    public interface Handler {
        HookDecision handle(HookEvent event);
    }
}
