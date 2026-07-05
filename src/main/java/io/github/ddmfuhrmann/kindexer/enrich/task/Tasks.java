package io.github.ddmfuhrmann.kindexer.enrich.task;

import io.github.ddmfuhrmann.kindexer.enrich.Deterministic;
import io.github.ddmfuhrmann.kindexer.enrich.EnrichmentTask;
import io.github.ddmfuhrmann.kindexer.enrich.EvidenceIndex;
import io.github.ddmfuhrmann.kindexer.model.CallGraph;
import io.github.ddmfuhrmann.kindexer.model.EntryPoints.EntryPoint;
import io.github.ddmfuhrmann.kindexer.util.Json;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
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

        // ---- Chunking: split by controller, scope the call graph + facts to each chunk ----------

        /** Auto mode target: ~90K chars of scoped material per chunk (≈40K tokens ≈ ~50s/call). */
        private static final int AUTO_CHUNK_CHARS = 90_000;

        public List<Object> chunks(Deterministic det, int chunkSize) {
            List<EntryPoint> all = det.entryPoints();
            // chunkSize > 0 → manual cap of endpoints per chunk; <= 0 → auto by material size.
            if (chunkSize > 0 && all.size() <= chunkSize) {
                return List.of(material(det)); // fits the manual cap: one prompt, unchanged material
            }
            if (chunkSize <= 0 && sizeChars(material(det)) <= AUTO_CHUNK_CHARS) {
                return List.of(material(det)); // whole material fits the budget: one prompt, unchanged
            }
            // Group by declaring class (a controller ≈ a feature) so a controller is never split;
            // pack whole class-groups until the next would exceed the bound. TreeMap = stable order.
            TreeMap<String, List<EntryPoint>> byClass = new TreeMap<>();
            for (EntryPoint ep : all) {
                byClass.computeIfAbsent(ep.className() == null ? "" : ep.className(), k -> new ArrayList<>()).add(ep);
            }
            List<Object> out = new ArrayList<>();
            List<EntryPoint> cur = new ArrayList<>();
            for (List<EntryPoint> grp : byClass.values()) {
                List<EntryPoint> candidate = new ArrayList<>(cur);
                candidate.addAll(grp);
                boolean over = chunkSize > 0
                        ? candidate.size() > chunkSize                       // manual: by endpoint count
                        : sizeChars(scopedMaterial(det, candidate)) > AUTO_CHUNK_CHARS; // auto: by size
                if (!cur.isEmpty() && over) {
                    out.add(scopedMaterial(det, cur));
                    cur = new ArrayList<>(grp);
                } else {
                    cur = candidate;
                }
            }
            if (!cur.isEmpty()) {
                out.add(scopedMaterial(det, cur));
            }
            return out;
        }

        private static int sizeChars(Object material) {
            try {
                return Json.pretty().writeValueAsString(material).length();
            } catch (Exception e) {
                return Integer.MAX_VALUE; // unserializable → force its own chunk
            }
        }

        /** Material for one chunk: its endpoints + the reachable sub-graph + facts scoped to it. */
        private Object scopedMaterial(Deterministic det, List<EntryPoint> eps) {
            CallGraph cg = det.callGraph();
            // entry-point root nodes (matched by file:line), then the reachable closure over edges.
            Set<String> reachable = new java.util.TreeSet<>();
            if (cg != null) {
                Map<String, String> nodeByLoc = new java.util.HashMap<>();
                for (CallGraph.Node n : cg.nodes()) {
                    nodeByLoc.put(n.file() + "\0" + n.line(), n.id());
                }
                for (EntryPoint ep : eps) {
                    String root = nodeByLoc.get(ep.file() + "\0" + ep.line());
                    if (root != null) {
                        reachable.add(root);
                    }
                }
                Map<String, List<String>> adj = new java.util.HashMap<>();
                for (CallGraph.Edge e : cg.edges()) {
                    adj.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.to());
                }
                Deque<String> q = new ArrayDeque<>(reachable);
                while (!q.isEmpty()) {
                    for (String nx : adj.getOrDefault(q.poll(), List.of())) {
                        if (reachable.add(nx)) {
                            q.add(nx);
                        }
                    }
                }
            }
            List<CallGraph.Node> nodes = new ArrayList<>();
            Set<String> classes = new java.util.TreeSet<>();
            if (cg != null) {
                for (CallGraph.Node n : cg.nodes()) {
                    if (reachable.contains(n.id())) {
                        nodes.add(n);
                        classes.add(n.className());
                    }
                }
            }
            for (EntryPoint ep : eps) {
                if (ep.className() != null) classes.add(ep.className());
            }
            List<CallGraph.Edge> edges = new ArrayList<>();
            if (cg != null) {
                for (CallGraph.Edge e : cg.edges()) {
                    if (reachable.contains(e.from()) && reachable.contains(e.to())) edges.add(e);
                }
            }
            Set<String> epIds = new LinkedHashSet<>();
            for (EntryPoint ep : eps) epIds.add(ep.id());

            Map<String, Object> m = new TreeMap<>();
            m.put("entryPoints", eps);
            m.put("nodes", nodes);
            m.put("edges", edges);
            m.put("stateMachines", det.stateMachines());          // small — shared context, keep all
            m.put("tests", det.testScenarios().stream()
                    .filter(t -> t.targetClass() != null && classes.contains(t.targetClass())).toList());
            m.put("throwSites", det.throwSites().stream()
                    .filter(t -> reachable.contains(t.nodeId())).toList());
            m.put("exceptionStatuses", det.exceptionStatuses()); // small — keep all
            m.put("inputConstraints", det.inputConstraints().stream()
                    .filter(ic -> epIds.contains(ic.entryPointId())).toList());
            m.put("guardChecks", det.guardChecks().stream()
                    .filter(g -> reachable.contains(g.nodeId())).toList());
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
                GROUPING: "feature" is the business CAPABILITY the flow belongs to — the area a
                product owner would name on a board (a noun / domain area), NOT the specific action
                and NOT "<Entity> · <verb>". The renderer groups every scenario under its feature, so
                a feature is meant to COLLECT several related flows: the create / read / transition
                flows of one capability roll up together and read as "<Capability> › <flow>". Litmus
                test: if each scenario ends up with its own distinct feature, or a feature name reads
                like a single verb on an entity, the grouping is too fine — merge it into the broader
                capability it serves. Let the number of features fall out of the domain; do not aim
                for any particular count (few or many are both fine if they reflect real capabilities).
                VOICE: write the scenario title and given/when/then as a product owner describing
                business behaviour and the value/intent of each step — not as a state machine. Prefer
                the business meaning over the mechanics: name the actor when the flow makes it explicit
                (e.g. the customer, an operator, the system), and say what an outcome enables when the
                material shows a downstream effect (e.g. an event that starts another flow). Good:
                "the customer's payment is confirmed and fulfilment can begin". Avoid the mechanical
                register: "the status field is set to PAID".
                GROUNDING: this richer wording MUST stay anchored to the provided material — never
                invent actors, motives, fields, entities, or downstream effects that aren't in it.
                These prose fields are NOT evidence-checked, so fabrication here slips through
                unvalidated: describe the real facts more vividly, do not add fiction. If the material
                doesn't show who acts or why, keep it neutral rather than guessing. In particular, do
                NOT name an actor or recipient (customer, user, operator, agent, carrier, warehouse,
                staff, …) unless that role literally appears in the material — the domain may have no
                such role, and naming one is both invention and a domain assumption. When the subject
                isn't in the material, use passive or neutral voice ("the order is cancelled", not
                "the customer cancels the order"; "dispatched for delivery", not "on its way to the
                customer").
                EXAMPLES (register only — a neutral domain, do NOT copy it into your output):
                  - The scenario TITLE is a behaviour, not a CRUD/HTTP verb:
                    mechanical: "Get invoice by id" -> "Looking up an invoice by id"
                    mechanical: "POST order"        -> "Placing an order"
                  - Kill the state-machine register; say the business event, not the field write.
                    mechanical: "the invoice transitions to ISSUED status" -> "the invoice is issued"
                    mechanical: "the status field is set to CLOSED"         -> "the ticket is closed"
                  - Add the consequence ONLY when the material actually shows one (an event handler,
                    a published event, a triggered flow):
                    grounded: "the subscription is activated, and the first billing cycle begins"
                    — include the second clause only if a billing-cycle flow is really in the material.
                  - No downstream effect in the material -> stop at the business event, invent nothing:
                    "the request is approved"  (NOT "…, unlocking downstream access" when nothing shows it).
                  - Do NOT name an actor/recipient absent from the material; use neutral/passive voice:
                    invented: "the shipment is dispatched, on its way to the customer"
                    grounded: "the shipment is dispatched for delivery"
                    invented: "an operator confirms delivery"  ->  grounded: "delivery is confirmed"
                  So: always upgrade the register (business verb, not "transitions to X status"); add
                  intent/consequence only where the material backs it; where a step is just a status
                  change with nothing more, a short business sentence is correct — do not pad it.
                OUTPUT: a JSON array only. Each item:
                  {"feature": <business capability that groups several flows — a domain area, not a
                               single action; e.g. "Catalog", "Fulfilment">,
                   "scenario": <the single flow named as a business behaviour a product owner would
                                recognize, e.g. "Placing an order", "Looking up an invoice",
                                "Confirming payment" — NOT a CRUD/HTTP verb ("Get order", "POST invoice")>,
                   "given": <precondition, business language>,
                   "when": <the business action — NEVER the HTTP method/path; e.g. "payment is
                            received", not "POST /orders/{id}/pay is called">,
                   "then": <business outcome, including its consequence/intent when the flow shows one
                            (e.g. an event that triggers downstream work)>,
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
                  - EXACTLY ONE scenario per endpoint id — one card, not one per branch. Do NOT split
                    an endpoint's happy path from its validation / error / security branches into
                    separate scenarios: those branches go in THIS scenario's coverage.untested (or are
                    noted as tested). Two scenarios sharing the same evidence.entryPoint is an error —
                    they render the same sequence diagram. One endpoint → one use case.
                  - 'nodes' should trace the flow (controller → service → domain; reads: controller →
                    read repository); invalid node ids are dropped from the display.
                  - 'verifiedBy' entries must be real "TestClass#method" from 'tests'; invalid ones are
                    dropped and 'gap' is recomputed.
                  - 'coverage' estimates test coverage of the WHOLE flow: cross the reachable throwSites
                    (error paths), inputConstraints and guardChecks against the tests. 'level' is 'full'
                    when the happy path AND the error/validation branches look tested, 'partial' when
                    some branches are, 'thin' when only the happy path (or nothing) is. 'untested' names
                    the real error/validation branches that appear to lack a test.
                  - 'feature' MUST group related scenarios (see GROUPING). A feature used by only one
                    scenario across the whole set is a smell — reconsider whether it belongs to a
                    broader capability. Do not name a feature after a single verb/action.
                  - 'when' is the business action, never the transport: do not echo the HTTP verb or
                    path. Good: "the order is cancelled". Bad: "POST /orders/{id}/cancel is called".
                  - A status change (one enum value → another, per the provided stateMachines) is a
                    lifecycle flow: set "type":"lifecycle" (not "happy-path").
                  - Priority heuristic: money/fiscal/auth flows = critical.
                  - No prose outside the JSON array.""";
        }

        public ArrayNode validate(JsonNode rawItems, EvidenceIndex idx) {
            ArrayNode out = array();
            // 1:1 design: one card per endpoint. A small chunk can make the model emit a card per
            // branch (login ok + each failure); keep the first per entryPoint, drop the rest — their
            // branches belong in coverage.untested, and duplicates render identical diagrams.
            Set<String> seenEntryPoints = new LinkedHashSet<>();
            for (JsonNode it : items(rawItems)) {
                JsonNode ev = it.get("evidence");
                // A use case MUST enter through a real endpoint.
                if (ev == null || !idx.hasEntryPoint(text(ev, "entryPoint")) || text(it, "scenario") == null) {
                    continue;
                }
                if (!seenEntryPoints.add(text(ev, "entryPoint"))) {
                    continue; // already have a scenario for this endpoint
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
