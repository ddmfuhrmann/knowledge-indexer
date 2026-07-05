package io.github.ddmfuhrmann.kindexer.enrich.task;

import io.github.ddmfuhrmann.kindexer.enrich.Deterministic;
import io.github.ddmfuhrmann.kindexer.enrich.EnrichmentTask;
import io.github.ddmfuhrmann.kindexer.enrich.EvidenceIndex;
import io.github.ddmfuhrmann.kindexer.model.CallGraph;
import io.github.ddmfuhrmann.kindexer.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The four enrichment tasks, each self-contained. Grouped in one file because they share the same
 * tiny shape (material + instructions + evidence-gated validation) and are read together.
 */
public final class Tasks {

    private Tasks() {}

    public static List<EnrichmentTask> all() {
        // Use-cases (Behaviors) subsume the old per-endpoint flow-summary + coverage tasks: each
        // use-case links to an endpoint and carries its own coverage. Kept alongside are the two
        // structural interpretations that don't fit the use-case card (state transitions, domains).
        return List.of(new Behaviors(), new StateTransitions(), new Domains());
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static ArrayNode array() {
        return Json.mapper().createArrayNode();
    }

    private static Iterable<JsonNode> items(JsonNode raw) {
        return raw != null && raw.isArray() ? raw : List.of();
    }

    // ---- Enrichment 1: state transitions --------------------------------------------------

    static final class StateTransitions implements EnrichmentTask {
        public String name() {
            return "stateTransitions";
        }

        public Object material(Deterministic det) {
            return det.stateMachines();
        }

        public String instructions() {
            return """
                Infer the state transitions of each status enum from its assignment sites.
                OUTPUT: a JSON array only. Each item:
                  {"from": <state>, "to": <state>, "trigger": <short cause>,
                   "evidence": {"class": <str>, "method": <str>, "file": <str>, "line": <int>}}
                RULES:
                  - Emit a transition ONLY if its evidence.file + evidence.line matches one of the
                    provided assignmentSites exactly. No matching site => do not emit the item.
                  - 'from' and 'to' must be values from the enum's 'states'.
                  - 'trigger' is one short phrase describing what causes the change.
                  - No prose outside the JSON array.""";
        }

        public ArrayNode validate(JsonNode rawItems, EvidenceIndex idx) {
            ArrayNode out = array();
            for (JsonNode it : items(rawItems)) {
                JsonNode ev = it.get("evidence");
                if (ev == null) {
                    continue;
                }
                String file = text(ev, "file");
                JsonNode lineNode = ev.get("line");
                int line = lineNode == null ? -1 : lineNode.asInt(-1);
                boolean anchored = idx.hasAssignmentAt(file, line);
                boolean shaped = text(it, "from") != null && text(it, "to") != null;
                if (anchored && shaped) {
                    out.add(it);
                }
            }
            return out;
        }
    }

    // ---- Enrichment: domain grouping -----------------------------------------------------

    static final class Domains implements EnrichmentTask {
        public String name() {
            return "domains";
        }

        public Object material(Deterministic det) {
            Map<String, String> classes = new TreeMap<>();
            if (det.callGraph() != null) {
                for (CallGraph.Node n : det.callGraph().nodes()) {
                    classes.putIfAbsent(n.className(), n.role());
                }
            }
            det.entities().forEach(e -> classes.putIfAbsent(e.name(), "domain"));
            List<Map<String, String>> classList = new ArrayList<>();
            classes.forEach((cls, role) -> {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("class", cls);
                row.put("role", role);
                classList.add(row);
            });
            Map<String, Object> m = new TreeMap<>();
            m.put("classes", classList);
            m.put("edges", det.callGraph() == null ? List.of() : det.callGraph().edges());
            return m;
        }

        public String instructions() {
            return """
                Group the provided classes into logical business domains for a mind map.
                OUTPUT: a JSON array only. Each item:
                  {"domain": <name>, "members": [<class>, ...], "rationale": <short reason>}
                RULES:
                  - Every 'members' entry MUST be one of the provided classes' 'class' names.
                  - Do not invent classes. Every class should appear in at most one domain.
                  - No prose outside the JSON array.""";
        }

        public ArrayNode validate(JsonNode rawItems, EvidenceIndex idx) {
            ArrayNode out = array();
            for (JsonNode it : items(rawItems)) {
                JsonNode members = it.get("members");
                if (members == null || !members.isArray()) {
                    continue;
                }
                ArrayNode kept = array();
                for (JsonNode member : members) {
                    if (idx.hasClass(member.asText())) {
                        kept.add(member.asText());
                    }
                }
                if (kept.isEmpty() || text(it, "domain") == null) {
                    continue;
                }
                var copy = it.deepCopy();
                ((com.fasterxml.jackson.databind.node.ObjectNode) copy).set("members", kept);
                out.add(copy);
            }
            return out;
        }
    }

    // ---- Enrichment 5: business behaviors (BDD living documentation) ---------------------

    static final class Behaviors implements EnrichmentTask {
        private static final Set<String> TYPES =
                Set.of("happy-path", "validation", "edge-case", "security", "lifecycle");
        private static final Set<String> PRIORITIES = Set.of("critical", "high", "medium");
        private static final Set<String> COVERAGE_LEVELS = Set.of("full", "partial", "thin");

        public String name() {
            return "behaviors";
        }

        public Object material(Deterministic det) {
            Map<String, Object> m = new TreeMap<>();
            m.put("entryPoints", det.entryPoints());
            m.put("nodes", det.callGraph() == null ? List.of() : det.callGraph().nodes());
            m.put("edges", det.callGraph() == null ? List.of() : det.callGraph().edges());
            m.put("stateMachines", det.stateMachines());
            m.put("tests", det.testScenarios());
            // Alternative-flow facts so the coverage estimate is grounded, not guessed.
            m.put("throwSites", det.throwSites());
            m.put("exceptionStatuses", det.exceptionStatuses());
            m.put("inputConstraints", det.inputConstraints());
            m.put("guardChecks", det.guardChecks());
            return m;
        }

        public String instructions() {
            return """
                Produce BUSINESS-FACING use cases (flows), in domain language a product owner would
                recognize — NOT test-method paraphrases.
                UNIT = ONE FLOW THROUGH ONE ENDPOINT. Emit one scenario PER meaningful endpoint, not
                one per capability. This is deliberate: each scenario renders its OWN sequence diagram
                traced from its OWN entry point, so a scenario that claims several endpoints would show
                a diagram for only one of them — a lie. Keep it 1:1 so the diagram is faithful.
                Reads are first-class: a GET is its own scenario (e.g. "Search products", "Get product
                by id") with a read flow (controller → read repository → db), separate from the write.
                GROUPING: put the business capability in "feature" (e.g. "Catalog · Brands"); the
                renderer groups scenarios under it. So "feature" is the capability header, "scenario"
                is the single flow — together they read like "Catalog · Brands › Create brand".
                OUTPUT: a JSON array only. Each item:
                  {"feature": <business capability / group header, e.g. "Catalog · Brands">,
                   "scenario": <single-flow title, e.g. "Create brand", "Search brands">,
                   "given": <precondition, business language>,
                   "when": <action>,
                   "then": <outcome>,
                   "type": one of ["happy-path","validation","edge-case","security","lifecycle"],
                   "priority": one of ["critical","high","medium"],
                   "verifiedBy": [<"TestClass#method">, ...],
                   "gap": <true iff verifiedBy is empty>,
                   "coverage": {"level": one of ["full","partial","thin"],
                                "untested": [<short aspect with no apparent test, e.g.
                                              "404 unknown salesperson2", "409 frozen edit">, ...]},
                   "evidence": {"entryPoint": <the flow's entry point id — REQUIRED, must be a real one>,
                                "covers": [<only truly REDUNDANT endpoint ids, e.g. an exact alias — NOT
                                            sibling CRUD verbs; normally leave empty>, ...],
                                "nodes": [<node id along the flow>, ...],
                                "assignmentSites": [<"file:line">, ...]}}
                RULES:
                  - evidence.entryPoint is MANDATORY and must be one of the provided entryPoints' id.
                    A use case without a valid endpoint is discarded.
                  - Cover EVERY endpoint by giving it its OWN scenario (reads included). Do NOT fold
                    sibling CRUD verbs into one card via 'covers'; 'covers' is only for a genuinely
                    redundant/aliased endpoint that shares the exact same flow. Invalid covers ids are
                    dropped. An endpoint with no scenario shows up under "Endpoints without a use case".
                  - 'nodes' should trace the flow (controller → service → domain; reads: controller →
                    read repository); invalid node ids are dropped from the display.
                  - 'verifiedBy' entries must be real "TestClass#method" from 'tests'; invalid ones are
                    dropped and 'gap' is recomputed.
                  - 'coverage' estimates test coverage of the WHOLE flow: cross the reachable throwSites
                    (error paths), inputConstraints and guardChecks against the tests. 'level' is 'full'
                    when the happy path AND the error/validation branches look tested, 'partial' when
                    some branches are, 'thin' when only the happy path (or nothing) is. 'untested' names
                    the real error/validation branches that appear to lack a test.
                  - Priority heuristic: money/fiscal/auth flows = critical.
                  - No prose outside the JSON array.""";
        }

        public ArrayNode validate(JsonNode rawItems, EvidenceIndex idx) {
            ArrayNode out = array();
            for (JsonNode it : items(rawItems)) {
                JsonNode ev = it.get("evidence");
                // A use case MUST enter through a real endpoint.
                if (ev == null || !idx.hasEntryPoint(text(ev, "entryPoint")) || text(it, "scenario") == null) {
                    continue;
                }
                var copy = (com.fasterxml.jackson.databind.node.ObjectNode) it.deepCopy();
                var evCopy = (com.fasterxml.jackson.databind.node.ObjectNode) copy.get("evidence");
                // Keep only real flow nodes so the displayed path is honest.
                ArrayNode nodes = array();
                for (JsonNode n : optArray(ev, "nodes")) {
                    if (idx.hasNode(n.asText())) {
                        nodes.add(n.asText());
                    }
                }
                evCopy.set("nodes", nodes);
                // Additional endpoints this use case covers — keep only real ones.
                ArrayNode covers = array();
                for (JsonNode c : optArray(ev, "covers")) {
                    if (idx.hasEntryPoint(c.asText())) {
                        covers.add(c.asText());
                    }
                }
                evCopy.set("covers", covers);
                // Filter verifiedBy to real tests, recompute gap, clamp enums.
                ArrayNode verified = array();
                for (JsonNode v : optArray(it, "verifiedBy")) {
                    if (idx.hasScenarioKey(v.asText())) {
                        verified.add(v.asText());
                    }
                }
                copy.set("verifiedBy", verified);
                copy.put("gap", verified.isEmpty());
                if (!TYPES.contains(text(it, "type"))) {
                    copy.put("type", "happy-path");
                }
                if (!PRIORITIES.contains(text(it, "priority"))) {
                    copy.put("priority", "medium");
                }
                JsonNode cov = copy.get("coverage");
                if (cov instanceof com.fasterxml.jackson.databind.node.ObjectNode co
                        && !COVERAGE_LEVELS.contains(text(co, "level"))) {
                    co.put("level", "partial");
                }
                out.add(copy);
            }
            return out;
        }

        private static Iterable<JsonNode> optArray(JsonNode node, String key) {
            JsonNode v = node == null ? null : node.get(key);
            return v != null && v.isArray() ? v : List.of();
        }

        private static int parseIntSafe(String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }

}
