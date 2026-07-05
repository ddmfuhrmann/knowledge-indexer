package io.github.ddmfuhrmann.kindexer.enrich;

import io.github.ddmfuhrmann.kindexer.model.CallGraph;
import io.github.ddmfuhrmann.kindexer.model.Entities.EntityModel;
import io.github.ddmfuhrmann.kindexer.model.EntryPoints.EntryPoint;
import io.github.ddmfuhrmann.kindexer.model.StateMachines.StateMachine;
import io.github.ddmfuhrmann.kindexer.model.TestScenarios.Scenario;
import io.github.ddmfuhrmann.kindexer.model.TestScenarios.TestUnit;

import java.util.HashSet;
import java.util.Set;

/**
 * The set of real code anchors extracted deterministically. Every enrichment item must resolve to
 * at least one of these or it is discarded — this is where "no evidence, no item" is enforced in
 * code rather than trusted from the model.
 */
public final class EvidenceIndex {

    private final Set<String> entryPointIds = new HashSet<>();
    private final Set<String> nodeIds = new HashSet<>();
    private final Set<String> classNames = new HashSet<>();
    private final Set<String> assignmentLines = new HashSet<>(); // "file:line"
    private final Set<String> scenarioKeys = new HashSet<>();     // "TestClass#method"

    public static EvidenceIndex build(Deterministic det) {
        EvidenceIndex idx = new EvidenceIndex();
        for (EntryPoint ep : det.entryPoints()) {
            idx.entryPointIds.add(ep.id());
            idx.classNames.add(ep.className());
        }
        CallGraph cg = det.callGraph();
        if (cg != null) {
            for (CallGraph.Node n : cg.nodes()) {
                idx.nodeIds.add(n.id());
                idx.classNames.add(n.className());
            }
            for (CallGraph.AssignmentSite s : cg.assignmentSites()) {
                idx.assignmentLines.add(s.file() + ":" + s.line());
            }
        }
        for (StateMachine sm : det.stateMachines()) {
            for (CallGraph.AssignmentSite s : sm.assignmentSites()) {
                idx.assignmentLines.add(s.file() + ":" + s.line());
            }
        }
        for (EntityModel e : det.entities()) {
            idx.classNames.add(e.name());
        }
        for (TestUnit t : det.testScenarios()) {
            for (Scenario s : t.scenarios()) {
                idx.scenarioKeys.add(t.testClass() + "#" + s.method());
            }
        }
        return idx;
    }

    public boolean hasEntryPoint(String id) {
        return id != null && entryPointIds.contains(id);
    }

    public boolean hasNode(String id) {
        return id != null && nodeIds.contains(id);
    }

    public boolean hasClass(String name) {
        return name != null && classNames.contains(name);
    }

    public boolean hasAssignmentAt(String file, int line) {
        return file != null && assignmentLines.contains(file + ":" + line);
    }

    public boolean hasScenario(String testClass, String method) {
        return scenarioKeys.contains(testClass + "#" + method);
    }

    /** {@code "TestClass#method"} anchor — used to validate a behavior's verifiedBy list. */
    public boolean hasScenarioKey(String key) {
        return key != null && scenarioKeys.contains(key);
    }
}
