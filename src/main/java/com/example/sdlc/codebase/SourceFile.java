package com.example.sdlc.codebase;

import java.util.List;

/**
 * Structural facts about one source file extracted by {@link CodebaseIndex}. {@code code} is the content
 * with comments removed.
 */
public record SourceFile(String path, String layer, String typeName, String kind, List<String> declaredTypes,
                         List<String> publicMethods, List<String> endpoints, boolean jpaEntity, String content,
                         String code) {

    public boolean declares(String type) {
        return declaredTypes.contains(type);
    }

    /** True if the symbol occurs in code (comments excluded, so prose mentions are not false positives). */
    public boolean references(String symbol) {
        return java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(symbol) + "\\b").matcher(code).find();
    }
}
