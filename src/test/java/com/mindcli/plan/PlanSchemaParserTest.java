package com.mindcli.plan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanSchemaParserTest {

    @Test
    void shouldDefaultOptionalRuntimeFieldsWhenMissing() throws Exception {
        PlanSchemaParser parser = new PlanSchemaParser(null);

        PlanSchema schema = parser.parse("""
                {
                  "summary": "读取并验证",
                  "tasks": [
                    {
                      "id": "read",
                      "description": "读取 pom.xml",
                      "type": "FILE_READ",
                      "dependencies": []
                    }
                  ]
                }
                """);

        assertEquals(2, schema.schemaVersion());
        PlanTaskSpec task = schema.tasks().get(0);
        assertEquals("REPLAN", task.degradation());
        assertTrue(task.critical());
        assertEquals(3, task.maxRetries());
        assertEquals(List.of(), task.expectedEvidence());
    }

    @Test
    void shouldValidateCanonicalPlanWithoutExpectedEvidence() throws Exception {
        PlanSchemaParser parser = new PlanSchemaParser(null);
        PlanSchemaValidator validator = new PlanSchemaValidator();

        PlanSchema schema = parser.parse("""
                {
                  "schemaVersion": 2,
                  "summary": "读取并验证",
                  "tasks": [
                    {
                      "id": "read",
                      "description": "读取 pom.xml",
                      "type": "FILE_READ",
                      "dependencies": []
                    },
                    {
                      "id": "verify",
                      "description": "验证项目结构",
                      "type": "VERIFICATION",
                      "dependencies": ["read"]
                    }
                  ]
                }
                """);

        assertTrue(validator.validate(schema).isValid());
    }

    @Test
    void shouldRejectInvalidExpectedEvidenceType() {
        PlanSchemaParser parser = new PlanSchemaParser(null);

        Exception error = assertThrows(Exception.class, () -> parser.parse("""
                {
                  "summary": "读取并验证",
                  "tasks": [
                    {
                      "id": "read",
                      "description": "读取 pom.xml",
                      "type": "FILE_READ",
                      "dependencies": [],
                      "expectedEvidence": "pom.xml exists"
                    }
                  ]
                }
                """));

        assertTrue(error.getMessage().contains("PLAN_INVALID_EVIDENCE"));
    }
}
