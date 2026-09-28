package com.example.sdlc.model;

import com.example.sdlc.model.Enums.RiskLevel;

import java.io.Serializable;
import java.util.List;

public record RiskAssessment(RiskLevel overall, List<RiskItem> risks, List<String> tradeOffs,
                             List<String> failureScenarios) implements Serializable {

    public record RiskItem(String id, String category, String description, RiskLevel severity,
                           String mitigation) implements Serializable {
    }

    public RiskAssessment {
        overall = overall == null ? RiskLevel.LOW : overall;
        risks = risks == null ? List.of() : List.copyOf(risks);
        tradeOffs = tradeOffs == null ? List.of() : List.copyOf(tradeOffs);
        failureScenarios = failureScenarios == null ? List.of() : List.copyOf(failureScenarios);
    }
}
