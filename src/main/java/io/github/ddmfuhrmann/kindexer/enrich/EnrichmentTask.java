package io.github.ddmfuhrmann.kindexer.enrich;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * One LLM enrichment task. It is fed only its {@link #material(Deterministic)} (the anchored
 * deterministic subset), declares a fixed output schema in {@link #instructions()}, and — crucially
 * — filters the model's raw output through {@link #validate} so that any item not backed by real
 * evidence is dropped. The LLM interprets; it never introduces structure.
 */
public interface EnrichmentTask {

    /** Stable key, also the manifest enrichment section name (e.g. "stateTransitions"). */
    String name();

    /** The deterministic subset this task interprets — serialized into the prompt and hashed. */
    Object material(Deterministic det);

    /** Task description + exact output schema + the evidence rule, handed to the model. */
    String instructions();

    /** Keep only items whose evidence resolves against the deterministic index. */
    ArrayNode validate(JsonNode rawItems, EvidenceIndex idx);
}
