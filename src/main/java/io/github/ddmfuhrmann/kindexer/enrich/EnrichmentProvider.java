package io.github.ddmfuhrmann.kindexer.enrich;

/**
 * Source of an LLM interpretation for a single task prompt. Two implementations:
 * the default file-based agent handoff (no provider object; see {@code Enricher}'s extract/assemble
 * split) and {@link HttpAnthropicProvider} for the headless {@code --provider sdk} path.
 */
public interface EnrichmentProvider {

    /** Identifier recorded in the manifest's {@code model} field, e.g. "sdk:claude-...". */
    String modelId();

    /** Raw completion text for {@code prompt}; the caller extracts the JSON array from it. */
    String complete(String prompt) throws Exception;
}
