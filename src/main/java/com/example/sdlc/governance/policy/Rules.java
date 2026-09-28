package com.example.sdlc.governance.policy;

import com.example.sdlc.model.Artifact;
import com.example.sdlc.model.Enums.ArtifactType;
import com.example.sdlc.model.Enums.ChangeType;
import com.example.sdlc.model.Enums.Severity;
import com.example.sdlc.model.PolicyViolation;
import com.example.sdlc.model.TestStrategy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Built-in guardrails, grouped by concern: SEC (security), CMP (compliance), CHG (change control), QA (quality).
 */
public final class Rules {

    private Rules() {
    }

    public static List<PolicyRule> defaults() {
        return List.of(new HardcodedSecrets(), new SqlInjection(), new DangerousApis(), new PiiLogging(),
                new PathScope(), new ImmutableMigrations(), new BlastRadius(), new Traceability(), new JavaSanity());
    }

    private static List<Artifact> sources(List<Artifact> artifacts) {
        return artifacts.stream()
                .filter(a -> a.type() == ArtifactType.CODE || a.type() == ArtifactType.TEST)
                .toList();
    }

    /** SEC-001: no credentials in source. */
    static final class HardcodedSecrets implements PolicyRule {
        private static final Pattern P = Pattern.compile(
                "(?i)(password|passwd|secret|api[_-]?key|access[_-]?token)\\s*[=:]\\s*\"[^\"]{6,}\""
                        + "|AKIA[0-9A-Z]{16}|sk-[A-Za-z0-9]{20,}|-----BEGIN (RSA |EC )?PRIVATE KEY-----");

        public String id() { return "SEC-001"; }
        public String description() { return "No hard-coded secrets or keys in source"; }

        public List<PolicyViolation> evaluate(List<Artifact> artifacts, PolicyContext ctx) {
            List<PolicyViolation> out = new ArrayList<>();
            for (Artifact a : artifacts) {
                if (a.type() == ArtifactType.DOC || a.type() == ArtifactType.ADR) continue;
                if (P.matcher(a.content()).find()) {
                    out.add(new PolicyViolation(id(), Severity.BLOCKER, a.path(),
                            "Possible hard-coded secret; load it from the environment / secret manager"));
                }
            }
            return out;
        }
    }

    /** SEC-002: no SQL built by string concatenation. */
    static final class SqlInjection implements PolicyRule {
        private static final Pattern P = Pattern.compile(
                "(?i)\"\\s*(select|update|delete|insert)\\b[^\"]*\"\\s*\\+\\s*[A-Za-z_]");

        public String id() { return "SEC-002"; }
        public String description() { return "SQL must be parameterised (no string concatenation)"; }

        public List<PolicyViolation> evaluate(List<Artifact> artifacts, PolicyContext ctx) {
            return sources(artifacts).stream()
                    .filter(a -> P.matcher(a.content()).find())
                    .map(a -> new PolicyViolation(id(), Severity.BLOCKER, a.path(),
                            "SQL built via string concatenation; use bind parameters"))
                    .toList();
        }
    }

    /** SEC-003: no process execution / native deserialisation. */
    static final class DangerousApis implements PolicyRule {
        private static final Pattern P = Pattern.compile(
                "Runtime\\.getRuntime\\(\\)\\.exec|new\\s+ProcessBuilder|new\\s+ObjectInputStream");

        public String id() { return "SEC-003"; }
        public String description() { return "No process execution or Java native deserialisation"; }

        public List<PolicyViolation> evaluate(List<Artifact> artifacts, PolicyContext ctx) {
            return artifacts.stream()
                    .filter(a -> a.type() == ArtifactType.CODE)
                    .filter(a -> P.matcher(a.content()).find())
                    .map(a -> new PolicyViolation(id(), Severity.BLOCKER, a.path(), "Dangerous API usage"))
                    .toList();
        }
    }

    /** CMP-001: personal data must not be logged. */
    static final class PiiLogging implements PolicyRule {
        private static final Pattern P = Pattern.compile(
                "(?i)log\\.(trace|debug|info|warn|error)\\([^;]*(getRemoteAddr|clientIp|email|password|ssn)");

        public String id() { return "CMP-001"; }
        public String description() { return "No personal data (IP, e-mail, credentials) in logs"; }

        public List<PolicyViolation> evaluate(List<Artifact> artifacts, PolicyContext ctx) {
            return sources(artifacts).stream()
                    .filter(a -> P.matcher(a.content()).find())
                    .map(a -> new PolicyViolation(id(), Severity.MAJOR, a.path(),
                            "Personal data written to logs; hash or drop it"))
                    .toList();
        }
    }

    /** CHG-001: agents may only write inside the allowed source/doc trees. */
    static final class PathScope implements PolicyRule {
        private static final List<String> ALLOWED = List.of(
                "src/main/java/", "src/test/java/", "src/main/resources/", "docs/", "README");

        public String id() { return "CHG-001"; }
        public String description() { return "Writes restricted to src/, docs/ (no traversal, no absolute paths)"; }

        public List<PolicyViolation> evaluate(List<Artifact> artifacts, PolicyContext ctx) {
            List<PolicyViolation> out = new ArrayList<>();
            for (Artifact a : artifacts) {
                String p = a.path();
                boolean bad = p.startsWith("/") || p.contains("..") || p.contains("\\") || p.matches("^[A-Za-z]:.*")
                        || ALLOWED.stream().noneMatch(p::startsWith);
                if (bad) {
                    out.add(new PolicyViolation(id(), Severity.BLOCKER, p, "Path outside the permitted change scope"));
                }
            }
            return out;
        }
    }

    /** CHG-002: released migrations are immutable; schema changes must be new, additive files. */
    static final class ImmutableMigrations implements PolicyRule {
        private static final Pattern DESTRUCTIVE = Pattern.compile("(?i)\\b(drop\\s+(table|column)|truncate)\\b");

        public String id() { return "CHG-002"; }
        public String description() { return "Existing migrations are immutable; new migrations must be additive"; }

        public List<PolicyViolation> evaluate(List<Artifact> artifacts, PolicyContext ctx) {
            List<PolicyViolation> out = new ArrayList<>();
            for (Artifact a : artifacts) {
                if (a.type() != ArtifactType.MIGRATION) continue;
                if (ctx.changeType() == ChangeType.BROWNFIELD && ctx.existingMigrations().contains(a.path())) {
                    out.add(new PolicyViolation(id(), Severity.BLOCKER, a.path(),
                            "Released migration modified; add a new versioned migration instead"));
                }
                if (DESTRUCTIVE.matcher(a.content()).find()) {
                    out.add(new PolicyViolation(id(), Severity.BLOCKER, a.path(),
                            "Destructive DDL requires a separate, human-approved expand/contract plan"));
                }
            }
            return out;
        }
    }

    /** CHG-003: brownfield edits to existing files must be inside the analysed impact set. */
    static final class BlastRadius implements PolicyRule {
        public String id() { return "CHG-003"; }
        public String description() { return "Brownfield changes stay within the analysed impact set"; }

        public List<PolicyViolation> evaluate(List<Artifact> artifacts, PolicyContext ctx) {
            if (ctx.changeType() != ChangeType.BROWNFIELD) return List.of();
            return artifacts.stream()
                    .filter(a -> a.type() == ArtifactType.CODE)
                    .filter(a -> ctx.existingFiles().contains(a.path()))
                    .filter(a -> !ctx.impactedPaths().contains(a.path()))
                    .map(a -> new PolicyViolation(id(), Severity.MAJOR, a.path(),
                            "Modifies an existing file that impact analysis did not identify (scope creep)"))
                    .toList();
        }
    }

    /** QA-001: every acceptance criterion is covered by a planned test; code ships with tests. */
    static final class Traceability implements PolicyRule {
        public String id() { return "QA-001"; }
        public String description() { return "Acceptance criteria traced to tests; code changes include tests"; }

        public List<PolicyViolation> evaluate(List<Artifact> artifacts, PolicyContext ctx) {
            List<PolicyViolation> out = new ArrayList<>();
            TestStrategy ts = ctx.testStrategy();
            Set<String> covered = ts == null ? Set.of()
                    : ts.cases().stream().map(TestStrategy.TestCase::covers).collect(Collectors.toSet());
            for (String ac : ctx.acceptanceCriteriaIds()) {
                if (!covered.contains(ac)) {
                    out.add(new PolicyViolation(id(), Severity.MAJOR, "test-strategy",
                            ac + " has no test case tracing to it"));
                }
            }
            boolean hasCode = artifacts.stream().anyMatch(a -> a.type() == ArtifactType.CODE);
            boolean hasTests = artifacts.stream().anyMatch(a -> a.type() == ArtifactType.TEST);
            if (hasCode && !hasTests) {
                out.add(new PolicyViolation(id(), Severity.MAJOR, "artifacts", "Code change without any test artifact"));
            }
            return out;
        }
    }

    /** QA-002: cheap structural sanity for generated Java (catches truncated LLM output). */
    static final class JavaSanity implements PolicyRule {
        private static final Pattern PACKAGE = Pattern.compile("(?m)^package\\s+[a-z][\\w.]*;");
        private static final Pattern TYPE = Pattern.compile("\\b(class|interface|record|enum)\\s+\\w+");

        public String id() { return "QA-002"; }
        public String description() { return "Generated Java is structurally complete"; }

        public List<PolicyViolation> evaluate(List<Artifact> artifacts, PolicyContext ctx) {
            List<PolicyViolation> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Artifact a : artifacts) {
                if (!seen.add(a.path())) {
                    out.add(new PolicyViolation(id(), Severity.MAJOR, a.path(), "Duplicate artifact path"));
                }
                if (!a.isJava()) continue;
                String code = stripLiterals(a.content());
                if (!PACKAGE.matcher(a.content()).find()) {
                    out.add(new PolicyViolation(id(), Severity.MAJOR, a.path(), "Missing package declaration"));
                }
                if (!TYPE.matcher(code).find()) {
                    out.add(new PolicyViolation(id(), Severity.MAJOR, a.path(), "No type declaration"));
                }
                if (balance(code, '{', '}') != 0 || balance(code, '(', ')') != 0) {
                    out.add(new PolicyViolation(id(), Severity.MAJOR, a.path(), "Unbalanced braces/parentheses"));
                }
                if (a.content().contains("TODO: implement")) {
                    out.add(new PolicyViolation(id(), Severity.MINOR, a.path(), "Contains unimplemented TODO"));
                }
            }
            return out;
        }

        private static int balance(String s, char open, char close) {
            int b = 0;
            for (char c : s.toCharArray()) {
                if (c == open) b++;
                else if (c == close) b--;
            }
            return b;
        }

        static String stripLiterals(String s) {
            return com.example.sdlc.codebase.JavaSource.stripCommentsAndLiterals(s);
        }
    }
}
