package com.example.sdlc.model;

import com.example.sdlc.model.Enums.RiskLevel;
import com.example.sdlc.model.Enums.Stage;

import java.io.Serializable;
import java.util.List;

public record EngineeringTask(String id, String title, Stage stage, List<String> dependsOn,
                              RiskLevel impact, String rationale) implements Serializable {

    public EngineeringTask {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        impact = impact == null ? RiskLevel.LOW : impact;
    }
}
