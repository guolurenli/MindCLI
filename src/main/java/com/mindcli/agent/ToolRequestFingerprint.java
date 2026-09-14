package com.mindcli.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.mindcli.platform.llm.LlmClient;
import com.mindcli.platform.serialization.JsonSupport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Produces deterministic fingerprints for one tool-request batch. */
final class ToolRequestFingerprint {
    private ToolRequestFingerprint() {}

    static String of(List<LlmClient.ToolCall> calls) {
        StringBuilder value = new StringBuilder();
        if (calls != null) {
            for (LlmClient.ToolCall call : calls) {
                if (call == null || call.function() == null) continue;
                value.append("call:").append(call.function().name()).append('|')
                        .append(canonicalJson(call.function().arguments())).append(';');
            }
        }
        return sha256(value.toString());
    }

    private static String canonicalJson(String raw) {
        if (raw == null || raw.isBlank()) return "";
        try {
            StringBuilder canonical = new StringBuilder();
            appendCanonical(JsonSupport.mapper().readTree(raw), canonical);
            return canonical.toString();
        } catch (Exception ignored) {
            return raw.trim();
        }
    }

    private static void appendCanonical(JsonNode node, StringBuilder out) throws Exception {
        if (node == null || node.isNull()) {
            out.append("null");
        } else if (node.isObject()) {
            out.append('{');
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) out.append(',');
                String name = names.get(i);
                out.append(JsonSupport.mapper().writeValueAsString(name)).append(':');
                appendCanonical(node.get(name), out);
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) out.append(',');
                appendCanonical(node.get(i), out);
            }
            out.append(']');
        } else {
            out.append(node.toString());
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
