package com.example.sdlc.governance;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

/**
 * Minimal JSON writer for audit lines and manifests (keeps the core free of a JSON library dependency).
 */
public final class Json {

    private Json() {
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb);
        return sb.toString();
    }

    private static void write(Object v, StringBuilder sb) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            Iterator<? extends Map.Entry<?, ?>> it = m.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<?, ?> e = it.next();
                string(String.valueOf(e.getKey()), sb);
                sb.append(':');
                write(e.getValue(), sb);
                if (it.hasNext()) sb.append(',');
            }
            sb.append('}');
        } else if (v instanceof Collection<?> c) {
            sb.append('[');
            Iterator<?> it = c.iterator();
            while (it.hasNext()) {
                write(it.next(), sb);
                if (it.hasNext()) sb.append(',');
            }
            sb.append(']');
        } else {
            string(String.valueOf(v), sb);
        }
    }

    private static void string(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
