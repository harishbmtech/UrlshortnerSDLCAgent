package com.example.sdlc.model;

import java.io.Serializable;
import java.util.List;

/**
 * Architecture/design output: components, API contract (OpenAPI), schema change and key decisions (ADR style).
 */
public record DesignSpec(String overview, List<String> components, String openApiYaml, String schemaDdl,
                         List<String> decisions) implements Serializable {

    public DesignSpec {
        components = components == null ? List.of() : List.copyOf(components);
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
        openApiYaml = openApiYaml == null ? "" : openApiYaml;
        schemaDdl = schemaDdl == null ? "" : schemaDdl;
    }
}
