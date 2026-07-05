package io.github.ddmfuhrmann.kindexer.enrich;

import io.github.ddmfuhrmann.kindexer.model.CallGraph;
import io.github.ddmfuhrmann.kindexer.model.Entities.EntityModel;
import io.github.ddmfuhrmann.kindexer.model.EntryPoints.EntryPoint;
import io.github.ddmfuhrmann.kindexer.model.Flows.ExceptionStatus;
import io.github.ddmfuhrmann.kindexer.model.Flows.GuardCheck;
import io.github.ddmfuhrmann.kindexer.model.Flows.InputConstraint;
import io.github.ddmfuhrmann.kindexer.model.Flows.ThrowSite;
import io.github.ddmfuhrmann.kindexer.model.Migrations.MigrationFk;
import io.github.ddmfuhrmann.kindexer.model.StateMachines.StateMachine;
import io.github.ddmfuhrmann.kindexer.model.TestScenarios.TestUnit;

import java.util.List;

/**
 * The complete deterministic layer, passed to every enrichment task as its only source of truth.
 * Tasks read subsets of this; the {@link EvidenceIndex} built from it is what validates that each
 * LLM claim points at something real.
 */
public record Deterministic(
        List<EntityModel> entities,
        List<EntryPoint> entryPoints,
        CallGraph callGraph,
        List<StateMachine> stateMachines,
        List<TestUnit> testScenarios,
        List<MigrationFk> migrations,
        List<ThrowSite> throwSites,
        List<ExceptionStatus> exceptionStatuses,
        List<InputConstraint> inputConstraints,
        List<GuardCheck> guardChecks) {

    public EvidenceIndex evidenceIndex() {
        return EvidenceIndex.build(this);
    }
}
