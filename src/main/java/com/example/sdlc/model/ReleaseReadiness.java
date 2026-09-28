package com.example.sdlc.model;

import java.io.Serializable;
import java.util.List;

public record ReleaseReadiness(boolean ready, int score, List<String> passedChecks, List<String> blockers,
                               String recommendation) implements Serializable {

    public ReleaseReadiness {
        passedChecks = passedChecks == null ? List.of() : List.copyOf(passedChecks);
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
    }
}
