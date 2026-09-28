package com.example.sdlc.codebase;

/**
 * Tiny Java lexer helper shared by static analysis (impact analysis and policy rules).
 */
public final class JavaSource {

    private JavaSource() {
    }

    /**
     * Removes comments, string/char literals and text blocks in a single pass (literals become empty quotes),
     * so that braces, URLs or identifiers inside them are ignored by structural checks.
     */
    public static String stripCommentsAndLiterals(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (s.startsWith("\"\"\"", i)) {
                int end = s.indexOf("\"\"\"", i + 3);
                i = end < 0 ? n : end + 3;
                out.append("\"\"");
            } else if (c == '"' || c == '\'') {
                char quote = c;
                i++;
                while (i < n && s.charAt(i) != quote && s.charAt(i) != '\n') {
                    i += s.charAt(i) == '\\' ? 2 : 1;
                }
                i++;
                out.append(quote).append(quote);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
