package com.example.sdlc.agents.offline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodeTemplatesTest {

    @Test
    void patchPreservesCrLfLineEndings() {
        String content = "class ShortUrl {\r\n    private boolean active = true;\r\n}\r\n";

        String patched = CodeTemplates.patch("ShortUrl.java", content,
                "    private boolean active = true;\n",
                "    private boolean active = false;\n");

        assertEquals("class ShortUrl {\r\n    private boolean active = false;\r\n}\r\n", patched);
    }
}