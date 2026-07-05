package io.github.ddmfuhrmann.kindexer.model;

import java.util.List;

/**
 * Deterministic call graph (Extractor 3): resolved method-to-method edges starting from entry
 * points, plus the assignment sites of enum-typed fields (raw material for state machines). Every
 * edge is backed by a real, symbol-resolved call site — unresolved calls are dropped, never
 * guessed. This whole structure is what feeds enrichment; the LLM output never feeds back here.
 */
public record CallGraph(
        List<Node> nodes,
        List<Edge> edges,
        List<AssignmentSite> assignmentSites) {

    /** A method reachable in the graph. {@code id} = fully-qualified {@code Class#method}. */
    public record Node(
            String id,
            String className,
            String method,
            String file,
            int line,
            String role,        // controller | service | repository | domain | other
            String returnType) {} // declared return type ("void" when none) — for sequence returns

    /** A resolved call from one node to another, at a specific source line. */
    public record Edge(
            String from,
            String to,
            String file,
            int line) {}

    /** Where an enum-typed field receives a value — the anchor for state-transition inference. */
    public record AssignmentSite(
            String enumType,
            String field,
            String value,
            String className,
            String method,
            String file,
            int line) {}
}
