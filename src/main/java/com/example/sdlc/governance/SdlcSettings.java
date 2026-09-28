package com.example.sdlc.governance;

import com.example.sdlc.model.Enums.RiskLevel;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

/**
 * Governance knobs for the orchestrator (framework-free; bound from {@code sdlc.*} properties).
 *
 * @param maxImplementationAttempts bounded implement -> validate retries before rollback
 * @param maxReplans                bounded dynamic re-planning (clarification / rejection loops)
 * @param maxNodeExecutions         autonomy budget per run; exceeding it safe-stops the run
 * @param nodeRetryMaxAttempts      transient-failure retries per agent call before fallback
 * @param nodeRetryBackoff          base backoff (exponential) between retries
 * @param designApprovalThreshold   risk level at/above which the design gate requires a human
 * @param sourceRoot                code the brownfield analysis reasons about
 * @param migrationsRoot            existing DB migrations (immutable once released)
 * @param outputDir                 where approved artifacts are published
 * @param approvers                 identities allowed to sign off human checkpoints
 */
public record SdlcSettings(int maxImplementationAttempts, int maxReplans, int maxNodeExecutions,
                           int nodeRetryMaxAttempts, Duration nodeRetryBackoff, RiskLevel designApprovalThreshold,
                           Path sourceRoot, Path migrationsRoot, Path outputDir, Set<String> approvers) {

    public static SdlcSettings defaults() {
        return new SdlcSettings(3, 2, 60, 2, Duration.ofMillis(50), RiskLevel.HIGH,
                Path.of("src/main/java/com/example/shortener"), Path.of("src/main/resources/db/migration"),
                Path.of("build/sdlc-output"), Set.of("tech-lead", "eng-manager", "release-manager"));
    }

    public SdlcSettings withOutputDir(Path dir) {
        return new SdlcSettings(maxImplementationAttempts, maxReplans, maxNodeExecutions, nodeRetryMaxAttempts,
                nodeRetryBackoff, designApprovalThreshold, sourceRoot, migrationsRoot, dir, approvers);
    }
}
