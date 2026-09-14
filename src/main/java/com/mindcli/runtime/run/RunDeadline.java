package com.mindcli.runtime.run;

import com.mindcli.platform.config.ConfigValueResolver;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/** Persisted absolute deadline shared by a run and its child attempts. Zero disables it. */
public final class RunDeadline {
    public static final String METADATA_KEY = "runDeadlineEpochMillis";
    public static final String DESCRIPTION = "RUN_TIMEOUT: run 运行时限已到或 deadline 非法，停止启动新调用";

    private RunDeadline() {}

    static Map<String, String> initialize(Instant startedAt, Map<String, String> metadata) {
        Map<String, String> values = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        if (!values.containsKey(METADATA_KEY)) {
            int seconds = ConfigValueResolver.current().resolveInt(
                    "mindcli.react.max.duration.seconds", "MINDCLI_REACT_MAX_DURATION_SECONDS", 3_600);
            long deadline;
            try {
                deadline = seconds == 0 ? 0 : seconds < 0 ? -1
                        : Math.addExact(startedAt.toEpochMilli(), seconds * 1_000L);
            } catch (ArithmeticException e) {
                deadline = -1;
            }
            values.put(METADATA_KEY, Long.toString(deadline));
        }
        return Map.copyOf(values);
    }

    public static AgentRunContext restoreContext(String runId, AgentMode mode, String input,
                                                 String workspace, List<AgentRunEvent> events) {
        AgentRunEvent start = events.stream()
                .filter(event -> event.type() == AgentRunEventType.RUN_STARTED).findFirst()
                .orElse(events.isEmpty() ? null : events.get(0));
        Map<String, String> metadata = new LinkedHashMap<>(start == null ? Map.of() : start.attributes());
        // Legacy runs may have persisted their deadline on the first resume.
        if (!metadata.containsKey(METADATA_KEY)) {
            events.stream().filter(event -> event.attributes().containsKey(METADATA_KEY))
                    .findFirst().ifPresent(event -> metadata.put(METADATA_KEY,
                            event.attributes().get(METADATA_KEY)));
        }
        metadata.put("resumed", "true");
        return new AgentRunContext(runId, mode == null ? AgentMode.REACT : mode, input, workspace,
                start == null ? Instant.EPOCH : start.timestamp(), metadata);
    }

    public static long epochMillis(AgentRunContext context) {
        if (context == null) return 0;
        try {
            return Long.parseLong(context.metadata().get(METADATA_KEY));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    public static boolean isExpired(AgentRunContext context) {
        long deadline = epochMillis(context);
        return deadline != 0 && (deadline < 0 || System.currentTimeMillis() >= deadline);
    }

    public static void requireActive(AgentRunContext context) throws IOException {
        if (isExpired(context)) throw new IOException("run deadline 已到或非法，停止启动新调用");
    }
}
