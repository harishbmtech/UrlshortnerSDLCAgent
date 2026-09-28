package com.example.sdlc;

import com.example.sdlc.governance.policy.PolicyContext;
import com.example.sdlc.governance.policy.PolicyEngine;
import com.example.sdlc.model.Artifact;
import com.example.sdlc.model.Enums.ArtifactType;
import com.example.sdlc.model.Enums.ChangeType;
import com.example.sdlc.model.PolicyViolation;
import com.example.sdlc.model.TestStrategy;
import com.example.sdlc.model.ValidationReport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyEngineTest {

    private final PolicyEngine engine = PolicyEngine.withDefaults();
    private final TestStrategy covering = new TestStrategy(
            List.of(new TestStrategy.TestCase("TC-1", "x", "unit", "AC-1")), List.of("unit"), "");

    private static Artifact code(String path, String content) {
        return Artifact.of(ArtifactType.CODE, path, content, "test", 1, List.of());
    }

    private static Artifact test(String path) {
        return Artifact.of(ArtifactType.TEST, path, "package a;\nclass T { }\n", "test", 1, List.of());
    }

    private ValidationReport run(ChangeType type, Set<String> existing, Set<String> impacted, Artifact... artifacts) {
        return engine.evaluate(List.of(artifacts),
                new PolicyContext(type, existing, impacted, Set.of("src/main/resources/db/migration/V1__init.sql"),
                        List.of("AC-1"), covering), 1);
    }

    private static boolean has(ValidationReport r, String rule) {
        return r.violations().stream().map(PolicyViolation::ruleId).anyMatch(rule::equals);
    }

    @Test
    void cleanChangePasses() {
        ValidationReport r = run(ChangeType.GREENFIELD, Set.of(), Set.of(),
                code("src/main/java/a/A.java", "package a;\nclass A { String s = \"{ not a brace\"; }\n"),
                test("src/test/java/a/ATest.java"));
        assertTrue(r.passed(), r.violations().toString());
    }

    @Test
    void secretsSqlConcatenationAndDangerousApisAreBlocked() {
        ValidationReport r = run(ChangeType.GREENFIELD, Set.of(), Set.of(),
                code("src/main/java/a/A.java", """
                        package a;
                        class A {
                            String apiKey = "sk-abcdefghijklmnopqrstuvwxyz";
                            String q(String id) { return "SELECT * FROM t WHERE id=" + id; }
                            void x() throws Exception { Runtime.getRuntime().exec("rm"); }
                        }
                        """),
                test("src/test/java/a/ATest.java"));
        assertFalse(r.passed());
        assertTrue(has(r, "SEC-001"));
        assertTrue(has(r, "SEC-002"));
        assertTrue(has(r, "SEC-003"));
    }

    @Test
    void changeControlRules() {
        ValidationReport r = run(ChangeType.BROWNFIELD,
                Set.of("src/main/java/a/A.java", "src/main/java/a/B.java"), Set.of("src/main/java/a/A.java"),
                code("src/main/java/a/B.java", "package a;\nclass B { }\n"),                                  // scope creep
                code("../etc/passwd", "x"),                                                                   // traversal
                Artifact.of(ArtifactType.MIGRATION, "src/main/resources/db/migration/V1__init.sql", "ALTER TABLE t ADD c INT;", "t", 1, List.of()),
                Artifact.of(ArtifactType.MIGRATION, "src/main/resources/db/migration/V2__x.sql", "DROP TABLE t;", "t", 1, List.of()),
                test("src/test/java/a/BTest.java"));
        assertTrue(has(r, "CHG-001"));
        assertTrue(has(r, "CHG-002"));
        assertTrue(has(r, "CHG-003"));
    }

    @Test
    void traceabilityAndStructureAreChecked() {
        ValidationReport r = engine.evaluate(List.of(code("src/main/java/a/A.java", "package a;\nclass A { void f( { }\n")),
                new PolicyContext(ChangeType.GREENFIELD, Set.of(), Set.of(), Set.of(), List.of("AC-1", "AC-2"), covering), 1);
        assertTrue(r.violations().stream().anyMatch(v -> v.ruleId().equals("QA-001") && v.message().contains("AC-2")));
        assertTrue(r.violations().stream().anyMatch(v -> v.ruleId().equals("QA-001") && v.message().contains("without any test")));
        assertTrue(has(r, "QA-002"));
    }

    @Test
    void personalDataInLogsIsFlagged() {
        ValidationReport r = run(ChangeType.GREENFIELD, Set.of(), Set.of(),
                code("src/main/java/a/A.java", "package a;\nclass A { void f(Object req) { log.info(\"ip {}\", req.getRemoteAddr()); } }\n"),
                test("src/test/java/a/ATest.java"));
        assertTrue(has(r, "CMP-001"));
        assertFalse(r.passed());
    }
}
