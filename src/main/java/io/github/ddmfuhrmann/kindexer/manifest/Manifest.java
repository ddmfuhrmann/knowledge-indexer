package io.github.ddmfuhrmann.kindexer.manifest;

import java.util.Map;

/**
 * The single source of truth. Everything the HTML renders comes from here and nowhere else.
 * {@code artifacts} holds the deterministic layer (each hashed by its own content);
 * {@code enrichment} holds the LLM layer (each keyed by the {@code sourceHash} of the
 * deterministic material it interpreted). Keys are serialized alphabetically for stable diffs.
 */
public record Manifest(
        ProjectInfo project,
        Map<String, Artifact> artifacts,
        Map<String, EnrichmentSection> enrichment) {

    public record ProjectInfo(
            String name,
            String generatedAt,  // git commit time, not wall clock (determinism)
            String commit,
            String commitMessage) {}

    /** A deterministic artifact and the hash of its own canonical content. */
    public record Artifact(String contentHash, Object data) {}

    /**
     * An enrichment section. {@code sourceHash} is the contentHash of the deterministic input
     * that produced it (the cache key). {@code model} records who interpreted it
     * ("agent:claude-code", "sdk:claude-...", or "none"). {@code data} is the validated, evidence
     * -anchored output — empty list when enrichment was skipped or failed.
     */
    public record EnrichmentSection(String sourceHash, String model, Object data) {}
}
