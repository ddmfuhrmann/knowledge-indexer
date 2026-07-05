package io.github.ddmfuhrmann.kindexer.enrich;

import io.github.ddmfuhrmann.kindexer.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Content-addressed enrichment cache. Keyed by the {@code sourceHash} of the deterministic material
 * <em>and</em> the task's {@code promptVersion} (a hash of its instructions), so re-running against
 * unchanged code and an unchanged prompt reuses the stored result and never re-consults the LLM —
 * but editing a task's instructions invalidates only that task's entries. Committing this directory
 * makes an enrichment run reproducible offline (the recorded-cache demo path).
 */
public final class EnrichmentCache {

    private final Path dir;

    public EnrichmentCache(Path repoRoot) {
        this.dir = repoRoot.resolve(".knowledge-index/cache");
    }

    /** A cached interpretation: who produced it and the validated data array. */
    public record Entry(String model, JsonNode data) {}

    public Optional<Entry> read(String sourceHash, String promptVersion) {
        Path file = fileFor(sourceHash, promptVersion);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            JsonNode root = Json.mapper().readTree(Files.readString(file));
            JsonNode data = root.get("data");
            String model = root.hasNonNull("model") ? root.get("model").asText() : "cache";
            return Optional.of(new Entry(model, data == null ? Json.mapper().createArrayNode() : data));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public void write(String sourceHash, String promptVersion, String model, JsonNode data) {
        try {
            Files.createDirectories(dir);
            ObjectNode root = Json.mapper().createObjectNode();
            root.put("sourceHash", sourceHash);
            root.put("promptVersion", promptVersion);
            root.put("model", model);
            root.set("data", data);
            Files.writeString(fileFor(sourceHash, promptVersion), Json.pretty().writeValueAsString(root) + "\n");
        } catch (IOException e) {
            // Cache is an optimization; a write failure must not break generation.
        }
    }

    private Path fileFor(String sourceHash, String promptVersion) {
        String key = sourceHash.startsWith("sha256:") ? sourceHash.substring(7) : sourceHash;
        return dir.resolve(key + "-" + promptVersion + ".json");
    }
}
