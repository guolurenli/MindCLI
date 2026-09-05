package com.mindcli.runtime.run.recovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mindcli.platform.serialization.JsonSupport;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Canonical JSON codec for Team parent plan and step checkpoints. */
public final class TeamCheckpointCodec {
    private static final int VERSION = 1;
    private static final Set<String> TYPES = Set.of(
            "PLANNING", "FILE_READ", "FILE_WRITE", "COMMAND", "ANALYSIS", "VERIFICATION");
    private static final Set<String> STATUSES = Set.of(
            "PENDING", "RUNNING", "COMPLETED", "FAILED", "SKIPPED");
    private static final Set<String> PHASES = Set.of("", "EXECUTING", "REVIEWING", "AWAITING_MERGE");

    public String encodePlan(TeamResumeState state) {
        String error = validate(state);
        if (error != null) throw new IllegalArgumentException(error);
        try {
            ObjectNode root = JsonSupport.mapper().createObjectNode();
            root.put("schemaVersion", state.schemaVersion());
            root.put("planVersion", state.planVersion());
            ArrayNode steps = root.putArray("steps");
            for (TeamStepResumeState step : state.steps()) writeStep(steps.addObject(), step);
            return JsonSupport.mapper().writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalArgumentException("Team checkpoint 编码失败: " + message(e), e);
        }
    }

    public TeamResumeState decodePlan(String planJson) {
        if (planJson == null || planJson.isBlank()) return TeamResumeState.unavailable("Team checkpoint 损坏: planJson 为空");
        try {
            JsonNode root = JsonSupport.mapper().readTree(planJson);
            if (root == null || !root.isObject()) throw new IllegalArgumentException("根节点必须是 object");
            int schema = requiredInt(root, "schemaVersion");
            int version = requiredInt(root, "planVersion");
            if (schema != VERSION) throw new IllegalArgumentException("不支持的 schemaVersion: " + schema);
            if (version != VERSION) throw new IllegalArgumentException("不支持的 planVersion: " + version);
            JsonNode nodes = root.get("steps");
            if (nodes == null || !nodes.isArray()) throw new IllegalArgumentException("steps 必须是 array");
            List<TeamStepResumeState> steps = new ArrayList<>();
            for (JsonNode node : nodes) steps.add(decodeStep(node));
            TeamResumeState state = new TeamResumeState(true, schema, version, steps, "");
            String error = validate(state);
            if (error != null) throw new IllegalArgumentException(error);
            return state;
        } catch (Exception e) {
            return TeamResumeState.unavailable("Team checkpoint 损坏: " + message(e));
        }
    }

    public String encodeStepIds(List<String> ids) {
        validateIds(ids);
        try {
            ArrayNode node = JsonSupport.mapper().createArrayNode();
            ids.forEach(node::add);
            return JsonSupport.mapper().writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalArgumentException("step IDs 编码失败: " + message(e), e);
        }
    }

    public List<String> decodeStepIds(String json) {
        try {
            JsonNode node = JsonSupport.mapper().readTree(json);
            if (node == null || !node.isArray()) throw new IllegalArgumentException("step IDs 必须是 array");
            List<String> ids = new ArrayList<>();
            for (JsonNode value : node) {
                if (!value.isTextual()) throw new IllegalArgumentException("step ID 必须是 string");
                ids.add(value.textValue());
            }
            validateIds(ids);
            return List.copyOf(ids);
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException iae) throw iae;
            throw new IllegalArgumentException("step IDs JSON 损坏: " + message(e), e);
        }
    }

    private static void writeStep(ObjectNode n, TeamStepResumeState s) {
        n.put("id", s.id()); n.put("description", s.description()); n.put("type", s.type());
        strings(n.putArray("dependencies"), s.dependencies()); strings(n.putArray("requiredTools"), s.requiredTools());
        n.put("preferredAgent", s.preferredAgent()); n.put("riskLevel", s.riskLevel()); n.put("status", s.status());
        n.put("phase", s.phase()); n.put("attempt", s.attempt()); n.put("result", s.result()); n.put("error", s.error());
        strings(n.putArray("childRunIds"), s.childRunIds());
    }

    private static TeamStepResumeState decodeStep(JsonNode n) {
        if (n == null || !n.isObject()) throw new IllegalArgumentException("step 必须是 object");
        return new TeamStepResumeState(text(n, "id", true), text(n, "description", true), text(n, "type", true),
                list(n, "dependencies"), list(n, "requiredTools"), text(n, "preferredAgent", false),
                text(n, "riskLevel", false), text(n, "status", true), text(n, "phase", false), integer(n, "attempt"),
                text(n, "result", false), text(n, "error", false), list(n, "childRunIds"));
    }

    private static String validate(TeamResumeState state) {
        if (state == null || !state.available()) return "不可编码不可用的 Team checkpoint";
        if (state.schemaVersion() != VERSION || state.planVersion() != VERSION) return "schemaVersion/planVersion 必须为 1";
        Map<String, TeamStepResumeState> byId = new LinkedHashMap<>();
        for (TeamStepResumeState s : state.steps()) {
            if (s == null || s.id().isBlank()) return "step id 不能为空";
            if (byId.put(s.id(), s) != null) return "step id 重复: " + s.id();
            if (s.description().isBlank()) return "step description 不能为空: " + s.id();
            if (!TYPES.contains(s.type())) return "未知 step type: " + s.type();
            if (!STATUSES.contains(s.status())) return "未知 step status: " + s.status();
            if (!PHASES.contains(s.phase())) return "未知 step phase: " + s.phase();
            if (s.attempt() < 0) return "step attempt 不能为负数: " + s.id();
            if (hasDuplicateOrBlank(s.dependencies()) || hasDuplicateOrBlank(s.requiredTools())) return "step list 字段不能包含空值或重复值: " + s.id();
            if (hasDuplicateOrBlank(s.childRunIds())) return "childRunIds 不能包含空值或重复值: " + s.id();
            for (String child : s.childRunIds()) if (!safeRunId(child)) return "childRunId 不安全: " + child;
        }
        for (TeamStepResumeState s : state.steps()) {
            for (String dep : s.dependencies()) {
                if (!byId.containsKey(dep)) return "step 依赖不存在: " + dep;
                if (dep.equals(s.id())) return "Team DAG 存在环: " + s.id();
            }
        }
        if (containsCycle(byId)) return "Team DAG 存在环";
        return null;
    }

    private static boolean containsCycle(Map<String, TeamStepResumeState> steps) {
        Map<String, Integer> in = new HashMap<>(); Map<String, List<String>> out = new HashMap<>();
        for (TeamStepResumeState s : steps.values()) { in.put(s.id(), s.dependencies().size()); for (String d : s.dependencies()) out.computeIfAbsent(d, k -> new ArrayList<>()).add(s.id()); }
        ArrayDeque<String> q = new ArrayDeque<>(); in.forEach((k,v) -> { if (v == 0) q.add(k); }); int count = 0;
        while (!q.isEmpty()) { String id = q.remove(); count++; for (String next : out.getOrDefault(id, List.of())) if (in.compute(next, (k,v) -> v - 1) == 0) q.add(next); }
        return count != steps.size();
    }

    private static boolean safeRunId(String id) { return id != null && id.matches("[A-Za-z0-9][A-Za-z0-9._-]*") && !id.contains(".."); }
    private static boolean hasDuplicateOrBlank(List<String> xs) { Set<String> seen = new HashSet<>(); for (String x : xs) if (x == null || x.isBlank() || !seen.add(x)) return true; return false; }
    private static void validateIds(List<String> ids) { if (ids == null || ids.isEmpty()) throw new IllegalArgumentException("step IDs 不能为空"); if (hasDuplicateOrBlank(ids)) throw new IllegalArgumentException("step IDs 不能包含空值或重复值"); }
    private static void strings(ArrayNode n, List<String> xs) { xs.forEach(n::add); }
    private static String text(JsonNode n, String f, boolean nonblank) { JsonNode v=n.get(f); if(v==null||!v.isTextual()||(nonblank&&v.textValue().isBlank())) throw new IllegalArgumentException(f+" 必须是"+(nonblank?"非空 ":"")+"string"); return v.textValue(); }
    private static int integer(JsonNode n,String f){JsonNode v=n.get(f);if(v==null||!v.isIntegralNumber()||!v.canConvertToInt()||v.intValue()<0)throw new IllegalArgumentException(f+" 必须是非负整数");return v.intValue();}
    private static List<String> list(JsonNode n,String f){JsonNode v=n.get(f);if(v==null||!v.isArray())throw new IllegalArgumentException(f+" 必须是 string array");List<String> r=new ArrayList<>();for(JsonNode x:v){if(!x.isTextual())throw new IllegalArgumentException(f+" 必须是 string array");r.add(x.textValue());}return List.copyOf(r);}
    private static int requiredInt(JsonNode n,String f){return integer(n,f);}
    private static String message(Exception e){return e.getMessage()==null||e.getMessage().isBlank()?e.getClass().getSimpleName():e.getMessage();}
}
