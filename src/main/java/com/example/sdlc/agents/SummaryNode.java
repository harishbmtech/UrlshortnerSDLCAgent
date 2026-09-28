package com.example.sdlc.agents;

import com.example.sdlc.graph.SdlcState;
import com.example.sdlc.model.Enums.RunStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Final engineering summary: plan/rationale, artifacts, risks/trade-offs, validation, assumptions, limitations.
 */
public class SummaryNode extends AgentSupport {

    public SummaryNode(AgentContext ctx) {
        super(ctx);
    }

    @Override
    public String name() {
        return "summary";
    }

    @Override
    public Map<String, Object> apply(SdlcState state) throws IOException {
        String published = state.publishedTo().orElse(null);
        String md = SummaryWriter.write(state, RunStatus.COMPLETED, null, published);
        if (published != null) {
            Files.writeString(Path.of(published, "ENGINEERING-SUMMARY.md"), md, StandardCharsets.UTF_8);
        }
        return Map.of(SdlcState.SUMMARY, md, SdlcState.STATUS, RunStatus.COMPLETED.name(),
                SdlcState.DECISIONS, List.of(decision("Run completed", "summary generated", "all")));
    }
}
