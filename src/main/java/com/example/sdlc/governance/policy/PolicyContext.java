package com.example.sdlc.governance.policy;

import com.example.sdlc.model.Enums.ChangeType;
import com.example.sdlc.model.TestStrategy;

import java.util.List;
import java.util.Set;

/**
 * Facts the policy rules evaluate artifacts against.
 */
public record PolicyContext(ChangeType changeType, Set<String> existingFiles, Set<String> impactedPaths,
                            Set<String> existingMigrations, List<String> acceptanceCriteriaIds,
                            TestStrategy testStrategy) {
}
