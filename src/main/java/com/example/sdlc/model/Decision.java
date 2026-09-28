package com.example.sdlc.model;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * Decision lineage entry: what was decided, by which node/actor, why, and on which inputs it was based.
 */
public record Decision(String node, String actor, String decision, String rationale, List<String> basedOn,
                       Instant at) implements Serializable {

    public Decision {
        basedOn = basedOn == null ? List.of() : List.copyOf(basedOn);
    }

    public static Decision of(String node, String actor, String decision, String rationale, String... basedOn) {
        return new Decision(node, actor, decision, rationale, List.of(basedOn), Instant.now());
    }
}
