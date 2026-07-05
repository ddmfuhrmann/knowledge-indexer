package io.github.ddmfuhrmann.kindexer.render;

import java.util.Locale;

/** Sanitizers that keep generated Mermaid syntactically valid regardless of source identifiers. */
public final class MermaidSafe {

    private MermaidSafe() {}

    /** A valid Mermaid node/identifier token: letters, digits, underscore only. */
    public static String id(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        String s = raw.replaceAll("[^A-Za-z0-9_]", "_");
        return Character.isLetter(s.charAt(0)) || s.charAt(0) == '_' ? s : "n_" + s;
    }

    /** A quoted label: strip characters that break Mermaid string parsing, collapse whitespace. */
    public static String label(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\"", "'")
                .replace("\n", " ")
                .replace("{", "(")
                .replace("}", ")")
                .replace("|", "/")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** An attribute type token for erDiagram (single word, no generics/spaces). */
    public static String type(String raw) {
        if (raw == null || raw.isBlank()) {
            return "Object";
        }
        return raw.replaceAll("[^A-Za-z0-9_]", "_");
    }

    /** Plain text for a mindmap node — no parentheses/brackets that mermaid treats as shapes. */
    public static String mindmapText(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        String s = raw.replaceAll("[()\\[\\]{}\"]", " ").replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? "unknown" : s;
    }
}
