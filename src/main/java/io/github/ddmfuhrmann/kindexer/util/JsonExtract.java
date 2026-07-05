package io.github.ddmfuhrmann.kindexer.util;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Pull the JSON array out of a model completion that may be wrapped in prose or code fences.
 * Returns an empty array node on any failure — enrichment must degrade, never throw.
 */
public final class JsonExtract {

    private JsonExtract() {}

    public static JsonNode firstArray(String raw) {
        if (raw != null) {
            String cleaned = raw.replace("```json", "```").trim();
            int fence = cleaned.indexOf("```");
            if (fence >= 0) {
                int end = cleaned.indexOf("```", fence + 3);
                if (end > fence) {
                    cleaned = cleaned.substring(fence + 3, end);
                }
            }
            int start = cleaned.indexOf('[');
            int last = cleaned.lastIndexOf(']');
            if (start >= 0 && last > start) {
                String slice = cleaned.substring(start, last + 1);
                try {
                    JsonNode node = Json.mapper().readTree(slice);
                    if (node.isArray()) {
                        return node;
                    }
                } catch (Exception ignored) {
                    // fall through to empty
                }
            }
        }
        return Json.mapper().createArrayNode();
    }
}
