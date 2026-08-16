package com.mindcli.agent.plan;

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
    void shouldParseAgentSelectionHintsFromSchemaVersion3() throws Exception {
        PlanSchemaParser parser = new PlanSchemaParser(null);

        PlanSchema schema = parser.parse("""
                {
                  "schemaVersion": 3,
                  "summary": "修改并验证",
                  "tasks": [
                    {
                      "id": "write",
                      "description": "修改文件",
                      "type": "FILE_WRITE",
                      "dependencies": [],
                      "requiredTools": ["read_file", "write_file"],
                      "preferredAgent": "code-writer",
                      "riskLevel": "medium",
                      "writeScope": ["src/main/java/auth/login/**"]
                    }
                  ]
                }
                """);

        PlanTaskSpec task = schema.tasks().get(0);
        assertEquals(3, schema.schemaVersion());
        assertEquals(List.of("read_file", "write_file"), task.requiredTools());
        assertEquals("code-writer", task.preferredAgent());
        assertEquals("medium", task.riskLevel());
        assertEquals(List.of("src/main/java/auth/login/**"), task.writeScope());
    }

    @Test
    void shouldInferRequiredToolsWhenAgentHintsAreMissing() throws Exception {
        PlanSchemaParser parser = new PlanSchemaParser(null);

        PlanSchema schema = parser.parse("""
                {
                  "summary": "验证",
                  "tasks": [
                    {
                      "id": "verify",
                      "description": "运行验证",
                      "type": "VERIFICATION",
                      "dependencies": []
                    }
                  ]
                }
                """);

        PlanTaskSpec task = schema.tasks().get(0);
        assertEquals(List.of("read_file", "grep_code", "execute_command"), task.requiredTools());
        assertEquals("", task.preferredAgent());
        assertEquals("low", task.riskLevel());
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
