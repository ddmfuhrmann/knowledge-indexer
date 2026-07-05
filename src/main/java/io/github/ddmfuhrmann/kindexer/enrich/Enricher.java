package io.github.ddmfuhrmann.kindexer.enrich;

import io.github.ddmfuhrmann.kindexer.enrich.task.Tasks;
import io.github.ddmfuhrmann.kindexer.hash.ContentHash;
import io.github.ddmfuhrmann.kindexer.manifest.Manifest.EnrichmentSection;
import io.github.ddmfuhrmann.kindexer.util.Json;
import io.github.ddmfuhrmann.kindexer.util.JsonExtract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Orchestrates the four enrichment tasks across the three run modes, always sharing one cache and
 * one evidence validator. The invariant everywhere: material comes from the deterministic layer, the
 * model only interprets it, and the interpretation is evidence-gated before it is trusted. LLM
 * output is never fed back into extraction.
 */
public final class Enricher {

    private final List<EnrichmentTask> tasks = Tasks.all();

    /** One task's resolved context: its anchored material, cache key and full prompt. */
    private record Ctx(EnrichmentTask task, Object material, String sourceHash, String prompt) {}

    private Ctx contextFor(EnrichmentTask task, Deterministic det) {
        Object material = task.material(det);
        String hash = ContentHash.of(material);
        String prompt = task.instructions()
                + "\n\nMATERIAL (deterministic — do not invent anything beyond this):\n"
                + writePretty(material)
                + "\n\nReturn ONLY the JSON array described above.";
        return new Ctx(task, material, hash, prompt);
    }

    /** {@code --no-llm}: every section present but empty, still keyed by its sourceHash. */
    public java.util.Map<String, EnrichmentSection> empty(Deterministic det) {
        java.util.Map<String, EnrichmentSection> out = new TreeMap<>();
        for (EnrichmentTask task : tasks) {
            Ctx ctx = contextFor(task, det);
            out.put(task.name(), new EnrichmentSection(ctx.sourceHash(), "none", Json.mapper().createArrayNode()));
        }
        return out;
    }

    /**
     * Agent phase 1: for each cache-miss, write a request file the agent (Claude Code) will answer.
     * Cache hits are returned immediately so unchanged material is never re-asked.
     */
    public java.util.Map<String, EnrichmentSection> emitRequests(Deterministic det, Path repoRoot, Path outDir) {
        EnrichmentCache cache = new EnrichmentCache(repoRoot);
        EvidenceIndex idx = det.evidenceIndex();
        Path reqDir = outDir.resolve("enrich/requests");
        java.util.Map<String, EnrichmentSection> out = new TreeMap<>();
        for (EnrichmentTask task : tasks) {
            Ctx ctx = contextFor(task, det);
            Optional<EnrichmentCache.Entry> hit = cache.read(ctx.sourceHash());
            if (hit.isPresent()) {
                out.put(task.name(), new EnrichmentSection(ctx.sourceHash(), hit.get().model(),
                        task.validate(hit.get().data(), idx)));
            } else {
                writeRequest(reqDir, ctx);
                out.put(task.name(), new EnrichmentSection(ctx.sourceHash(), "pending", Json.mapper().createArrayNode()));
            }
        }
        return out;
    }

    /**
     * Agent phase 2: fold in the agent's responses (validated + cached), keeping any cache hits.
     * A missing response leaves that section empty rather than failing the whole run.
     */
    public java.util.Map<String, EnrichmentSection> assembleFromResponses(Deterministic det, Path repoRoot, Path outDir) {
        EnrichmentCache cache = new EnrichmentCache(repoRoot);
        EvidenceIndex idx = det.evidenceIndex();
        Path respDir = outDir.resolve("enrich/responses");
        java.util.Map<String, EnrichmentSection> out = new TreeMap<>();
        for (EnrichmentTask task : tasks) {
            Ctx ctx = contextFor(task, det);
            Optional<EnrichmentCache.Entry> hit = cache.read(ctx.sourceHash());
            if (hit.isPresent()) {
                out.put(task.name(), new EnrichmentSection(ctx.sourceHash(), hit.get().model(),
                        task.validate(hit.get().data(), idx)));
                continue;
            }
            JsonNode raw = readResponse(respDir, task.name());
            if (raw == null) {
                out.put(task.name(), new EnrichmentSection(ctx.sourceHash(), "none", Json.mapper().createArrayNode()));
                continue;
            }
            ArrayNode validated = task.validate(raw, idx);
            cache.write(ctx.sourceHash(), "agent:claude-code", validated);
            out.put(task.name(), new EnrichmentSection(ctx.sourceHash(), "agent:claude-code", validated));
        }
        return out;
    }

    /** Headless {@code --provider sdk}: cache-or-call, validate, cache, all in one pass. */
    public java.util.Map<String, EnrichmentSection> runWithProvider(Deterministic det, Path repoRoot, EnrichmentProvider provider) {
        EnrichmentCache cache = new EnrichmentCache(repoRoot);
        EvidenceIndex idx = det.evidenceIndex();
        java.util.Map<String, EnrichmentSection> out = new TreeMap<>();
        for (EnrichmentTask task : tasks) {
            Ctx ctx = contextFor(task, det);
            Optional<EnrichmentCache.Entry> hit = cache.read(ctx.sourceHash());
            if (hit.isPresent()) {
                out.put(task.name(), new EnrichmentSection(ctx.sourceHash(), hit.get().model(),
                        task.validate(hit.get().data(), idx)));
                continue;
            }
            ArrayNode validated;
            String model;
            try {
                JsonNode raw = JsonExtract.firstArray(provider.complete(ctx.prompt()));
                validated = task.validate(raw, idx);
                model = provider.modelId();
                cache.write(ctx.sourceHash(), model, validated);
            } catch (Exception e) {
                // LLM failure must not break generation — leave the section empty and continue.
                validated = Json.mapper().createArrayNode();
                model = "none";
                System.err.println("[kindexer] enrichment '" + task.name() + "' failed: " + e.getMessage());
            }
            out.put(task.name(), new EnrichmentSection(ctx.sourceHash(), model, validated));
        }
        return out;
    }

    private void writeRequest(Path reqDir, Ctx ctx) {
        try {
            Files.createDirectories(reqDir);
            ObjectNode req = Json.mapper().createObjectNode();
            req.put("task", ctx.task().name());
            req.put("sourceHash", ctx.sourceHash());
            req.put("model", "agent");
            req.put("instructions", ctx.task().instructions());
            req.set("material", Json.mapper().valueToTree(ctx.material()));
            req.put("prompt", ctx.prompt());
            Files.writeString(reqDir.resolve(ctx.task().name() + ".json"),
                    Json.pretty().writeValueAsString(req) + "\n");
        } catch (IOException e) {
            System.err.println("[kindexer] could not write request for " + ctx.task().name() + ": " + e.getMessage());
        }
    }

    private JsonNode readResponse(Path respDir, String task) {
        Path file = respDir.resolve(task + ".json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return JsonExtract.firstArray(Files.readString(file));
        } catch (IOException e) {
            return null;
        }
    }

    private static String writePretty(Object value) {
        try {
            return Json.pretty().writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }
}
