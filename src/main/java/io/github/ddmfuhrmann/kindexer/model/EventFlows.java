package io.github.ddmfuhrmann.kindexer.model;

import java.util.List;

/**
 * Deterministic event choreography (Extractor 9): for each domain event, who publishes it and who
 * consumes it. Producers come from publish sites ({@code publishEvent(new X(...))}, aggregate
 * {@code registerEvent}, broker {@code send}/{@code publish}); consumers come from the event entry
 * points. Matched by the event's simple type name — the decoupled seam of a modular monolith.
 */
public final class EventFlows {

    private EventFlows() {}

    /** One side of an event edge — a class#method that publishes or consumes it. */
    public record Party(String className, String method, String file, int line, boolean async) {}

    public record EventFlow(String event, List<Party> producers, List<Party> consumers) {}
}
