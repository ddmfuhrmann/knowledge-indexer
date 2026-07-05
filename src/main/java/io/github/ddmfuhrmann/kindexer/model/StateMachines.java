package io.github.ddmfuhrmann.kindexer.model;

import java.util.List;

/**
 * Deterministic state-machine material (Extractor 4): a status/state enum, its declared states,
 * and the assignment sites where its value is set. Transitions are <b>not</b> inferred here — that
 * is enrichment. This layer only supplies the anchored raw facts.
 */
public final class StateMachines {

    private StateMachines() {}

    public record StateMachine(
            String enumType,
            String file,
            int line,
            List<String> states,
            List<CallGraph.AssignmentSite> assignmentSites) {}
}
