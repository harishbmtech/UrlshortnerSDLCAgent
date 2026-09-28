package com.example.sdlc.model;

import com.example.sdlc.model.Enums.ArtifactType;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * A reviewable engineering output with lineage: which node produced it, from which plan version, derived from what.
 */
public record Artifact(String id, ArtifactType type, String path, String content, String producedBy,
                       int planVersion, List<String> derivedFrom, String sha256) implements Serializable {

    public Artifact {
        derivedFrom = derivedFrom == null ? List.of() : List.copyOf(derivedFrom);
    }

    public static Artifact of(ArtifactType type, String path, String content, String producedBy,
                              int planVersion, List<String> derivedFrom) {
        String sha = sha256(content);
        String id = type.name().toLowerCase() + ":" + path;
        return new Artifact(id, type, path, content, producedBy, planVersion, derivedFrom, sha);
    }

    public static String sha256(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean isJava() {
        return path.endsWith(".java");
    }
}
