package io.github.ddmfuhrmann.kindexer.model;

/**
 * Deterministic entry-point catalog (Extractor 2). Each entry is where control enters the
 * application from the outside, tagged by category. Optional fields stay null when not applicable
 * to the category (e.g. {@code path} for a scheduled job).
 */
public final class EntryPoints {

    private EntryPoints() {}

    public record EntryPoint(
            String category,     // http | scheduled | event | cli
            String id,           // stable unique key, e.g. "http:GET /api/parties"
            String httpMethod,   // http
            String path,         // http
            String cron,         // scheduled (cron or fixedRate expr)
            String destination,  // event (topic/queue) when present
            String className,
            String method,
            String file,
            int line) {}
}
