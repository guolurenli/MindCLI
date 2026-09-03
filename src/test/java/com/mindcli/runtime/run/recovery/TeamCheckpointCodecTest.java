package com.mindcli.runtime.run.recovery;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TeamCheckpointCodecTest {
    private final TeamCheckpointCodec codec = new TeamCheckpointCodec();

    @Test
    void roundTripsEveryTeamStepField() {
        TeamResumeState state = new TeamResumeState(true, 1, 1, List.of(
                new TeamStepResumeState("step_1", "inspect code", "FILE_READ", List.of(),
                        List.of("read_file"), "explorer", "low", "COMPLETED", "", 0,
                        "evidence", "", List.of("child-execute", "child-review"))), "");

        assertEquals(state, codec.decodePlan(codec.encodePlan(state)));
    }

    @Test
    void rejectsDuplicateIdsUnknownDependenciesAndCycles() {
        TeamResumeState decoded = codec.decodePlan("""
                {"schemaVersion":1,"planVersion":1,"steps":[
                  {"id":"a","description":"a","type":"ANALYSIS","dependencies":["b"],
                   "requiredTools":[],"preferredAgent":"","riskLevel":"low","status":"PENDING",
                   "phase":"","attempt":0,"result":"","error":"","childRunIds":[]},
                  {"id":"b","description":"b","type":"ANALYSIS","dependencies":["a"],
                   "requiredTools":[],"preferredAgent":"","riskLevel":"low","status":"PENDING",
                   "phase":"","attempt":0,"result":"","error":"","childRunIds":[]}
                ]}
                """);
        assertFalse(decoded.available());
        assertTrue(decoded.reason().contains("环"));
    }

    @Test
    void stepIdsRoundTripAndRejectDuplicates() {
        assertEquals(List.of("step_1", "step_2"),
                codec.decodeStepIds(codec.encodeStepIds(List.of("step_1", "step_2"))));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decodeStepIds("[\"step_1\",\"step_1\"]"));
    }
}
