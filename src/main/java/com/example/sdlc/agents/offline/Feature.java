package com.example.sdlc.agents.offline;

import com.example.sdlc.model.Enums.RiskLevel;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Offline knowledge about one capability of a URL shortener: how to recognise it in a requirement, how to
 * accept it, what it risks, how to design and test it, and which code symbols it touches in the existing
 * codebase (for symbol-reference impact analysis).
 */
public record Feature(
        String key,
        String name,
        Pattern trigger,
        List<String> acceptanceCriteria,
        List<RiskSpec> risks,
        List<String> tradeOffs,
        List<String> components,
        List<String> endpoints,
        List<String> decisions,
        Impact impact) {

    public record RiskSpec(String category, String description, RiskLevel severity, String mitigation) {
    }

    /**
     * @param declares      types whose declaration must change
     * @param callSitesOf   types whose constructors/usages must be updated where they are called
     * @param reviewUsageOf types whose usages must be regression-reviewed but not edited
     * @param schemaChange  additive schema change, or null
     * @param existingCapabilitySymbol if this symbol exists in the codebase the feature is already implemented
     */
    public record Impact(List<String> declares, List<String> callSitesOf, List<String> reviewUsageOf,
                         String schemaChange, String existingCapabilitySymbol) {

        public static Impact none() {
            return new Impact(List.of(), List.of(), List.of(), null, null);
        }
    }

    public boolean matches(String text) {
        return trigger.matcher(text).find();
    }
}
