package io.github.ddmfuhrmann.kindexer.model;

import java.util.List;

/**
 * Deterministic test catalog (Extractor 5): behaviours per test unit. The link from a test class
 * to the class it exercises is best-effort by naming convention; {@code targetCertain} records
 * whether that link is confident so enrichment/rendering can flag uncertainty.
 */
public final class TestScenarios {

    private TestScenarios() {}

    public record TestUnit(
            String testClass,
            String file,
            String targetClass,   // best-effort, may be null
            boolean targetCertain,
            List<Scenario> scenarios) {}

    public record Scenario(
            String method,
            String displayName,   // @DisplayName when present, else null
            String description,   // humanized from method name / display name
            int line) {}
}
