package io.github.ddmfuhrmann.kindexer.model;

/**
 * Deterministic "alternative flow" material: what a flow can throw, how those exceptions map to HTTP
 * status, and the validation preconditions on an endpoint's input. All AST-derived — the error and
 * validation branches that complement the happy-path sequence.
 */
public final class Flows {

    private Flows() {}

    /** A {@code throw new X(...)} site: the exception, its (best-effort) guard condition and message. */
    public record ThrowSite(
            String exceptionType,
            String message,
            String condition,   // enclosing if-condition source, when present
            String className,
            String method,
            String nodeId,      // fqClass#method — matched against a flow's reachable nodes
            String file,
            int line) {}

    /** How an exception type resolves to an HTTP status (global handler or {@code @ResponseStatus}). */
    public record ExceptionStatus(
            String exceptionType,
            String status,      // e.g. "404 NOT_FOUND"
            String source) {}   // where the mapping was declared

    /** A Bean Validation precondition on an endpoint's {@code @RequestBody} command field. */
    public record InputConstraint(
            String entryPointId,
            String commandType,
            String field,
            String constraint,  // e.g. "NotNull", "Size"
            String detail) {}   // annotation params, when any

    /**
     * An imperative guard/assertion call inside the flow ({@code Guard.requireNonNull(customerId,…)},
     * {@code Objects.requireNonNull}, Spring {@code Assert.*}, Guava {@code Preconditions.*}). Read
     * from the call site so the guarded value is named per-field, unlike the generic condition inside
     * the guard helper. Scoped to a flow later by its {@code nodeId}.
     */
    public record GuardCheck(
            String field,       // the guarded value at the call site (e.g. "customerId")
            String constraint,  // normalized ("required", "must be > 0", …)
            String detail,      // the guard's message/label literal, when present
            String className,
            String method,
            String nodeId,      // fqClass#method — matched against a flow's reachable nodes
            String file,
            int line) {}
}
