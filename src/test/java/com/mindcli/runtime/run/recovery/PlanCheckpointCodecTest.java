package com.mindcli.runtime.run.recovery;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanCheckpointCodecTest {
    private final PlanCheckpointCodec codec = new PlanCheckpointCodec();

    @Test
    void roundTripsEveryPlanAndTaskField() {
        PlanResumeState state = new PlanResumeState(
                true,
                2,
                "plan-1",
                "goal",
                "summary",
                List.of(new PlanTaskResumeState(
                        "task_1",
                        "write file",
                        "FILE_WRITE",
                        List.of(),
                        false,
                        1,
                        "SKIP",
                        List.of("file exists"),
                        List.of("write_file"),
                        "worker",
                        "high",
                        "COMPLETED",
                        "done",
                        "",
                        1)),
                "");

        PlanResumeState decoded = codec.decode(codec.encode(state));

        assertTrue(decoded.available());
        assertEquals(state, decoded);
    }

    @Test
    void rejectsDuplicateTaskIdsAndMissingDependencies() {
        PlanResumeState decoded = codec.decode("""
                {"planVersion":1,"planId":"p","goal":"g","summary":"","tasks":[
                  {"id":"a","description":"a","type":"ANALYSIS","dependencies":[],"critical":true,
                   "maxRetries":0,"degradation":"REPLAN","expectedEvidence":[],"requiredTools":[],
                   "preferredAgent":"","riskLevel":"low","status":"PENDING","result":"","error":"","retryCount":0},
                  {"id":"a","description":"b","type":"ANALYSIS","dependencies":["missing"],"critical":true,
                   "maxRetries":0,"degradation":"REPLAN","expectedEvidence":[],"requiredTools":[],
                   "preferredAgent":"","riskLevel":"low","status":"PENDING","result":"","error":"","retryCount":0}
                ]}
                """);

        assertFalse(decoded.available());
        assertTrue(decoded.reason().contains("重复"));
    }

    @Test
    void rejectsUnknownStatus() {
        PlanResumeState decoded = codec.decode("""
                {"planVersion":1,"planId":"p","goal":"g","summary":"","tasks":[
                  {"id":"a","description":"a","type":"ANALYSIS","dependencies":[],"critical":true,
                   "maxRetries":0,"degradation":"REPLAN","expectedEvidence":[],"requiredTools":[],
                   "preferredAgent":"","riskLevel":"low","status":"UNKNOWN","result":"","error":"","retryCount":0}
                ]}
                """);

        assertFalse(decoded.available());
        assertTrue(decoded.reason().contains("status"));
    }

    @Test
    void rejectsCyclicDag() {
        PlanResumeState decoded = codec.decode("""
                {"planVersion":1,"planId":"p","goal":"g","summary":"","tasks":[
                  {"id":"a","description":"a","type":"ANALYSIS","dependencies":["b"],"critical":true,
                   "maxRetries":0,"degradation":"REPLAN","expectedEvidence":[],"requiredTools":[],
                   "preferredAgent":"","riskLevel":"low","status":"PENDING","result":"","error":"","retryCount":0},
                  {"id":"b","description":"b","type":"ANALYSIS","dependencies":["a"],"critical":true,
                   "maxRetries":0,"degradation":"REPLAN","expectedEvidence":[],"requiredTools":[],
                   "preferredAgent":"","riskLevel":"low","status":"PENDING","result":"","error":"","retryCount":0}
                ]}
                """);

        assertFalse(decoded.available());
        assertTrue(decoded.reason().contains("环"));
    }

    @Test
    void encodeRejectsUnavailableState() {
        PlanResumeState unavailable = PlanResumeState.unavailable("bad checkpoint");

        assertThrows(IllegalArgumentException.class, () -> codec.encode(unavailable));
    }
}
