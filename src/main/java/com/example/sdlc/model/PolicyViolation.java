package com.example.sdlc.model;

import com.example.sdlc.model.Enums.Severity;

import java.io.Serializable;

public record PolicyViolation(String ruleId, Severity severity, String artifactPath, String message) implements Serializable {
}
