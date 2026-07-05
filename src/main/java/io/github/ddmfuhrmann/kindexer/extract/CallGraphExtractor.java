package io.github.ddmfuhrmann.kindexer.extract;

import io.github.ddmfuhrmann.kindexer.ast.ProjectModel;
import io.github.ddmfuhrmann.kindexer.model.CallGraph;
import io.github.ddmfuhrmann.kindexer.model.CallGraph.AssignmentSite;
import io.github.ddmfuhrmann.kindexer.model.CallGraph.Edge;
import io.github.ddmfuhrmann.kindexer.model.CallGraph.Node;
import io.github.ddmfuhrmann.kindexer.model.EntryPoints.EntryPoint;
import io.github.ddmfuhrmann.kindexer.util.Ast;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.resolution.types.ResolvedType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Extractor 3 — the resolved call graph. From each entry-point method it follows method
 * invocations (JavaParser symbol resolution) across project classes, recording only edges it can
 * resolve to a project method; anything external or unresolvable is dropped, never invented. It
 * also collects the assignment sites of enum-typed fields — the raw material later handed to the
 * state-machine layer. Deterministic: declaration-indexed, BFS with a visited set, all output sorted.
 */
public final class CallGraphExtractor {

    /** Where a project method is declared, plus its architectural role. */
    private record Decl(String id, String className, String method, String file, int line, String role,
                        String returnType, MethodDeclaration node) {}

    public CallGraph extract(ProjectModel project, List<EntryPoint> entryPoints) {
        Map<String, Decl> declIndex = new HashMap<>();
        Set<String> enumQualifiedNames = new HashSet<>();
        Map<String, Map<String, String>> injectedFields = new HashMap<>();
        indexDeclarations(project, declIndex, enumQualifiedNames, injectedFields);
        // A field typed as a project enum is a value/domain type, not an out-of-source collaborator —
        // calls on it (name(), inherited Enum methods) must not be drawn as an infra boundary.
        Set<String> enumSimpleNames = new HashSet<>();
        for (String fq : enumQualifiedNames) {
            enumSimpleNames.add(simpleTypeName(fq));
        }
        injectedFields.values().forEach(f -> f.values().removeIf(enumSimpleNames::contains));

        Map<String, Node> nodes = new TreeMap<>();
        Set<String> edgeKeys = new HashSet<>();
        List<Edge> edges = new ArrayList<>();

        // Roots: entry-point methods that map to an indexed declaration. Match on (file, line) — the
        // only key unique across overloads AND same-named classes in different modules (a modular
        // monolith often has one XListener per module).
        List<String> roots = new ArrayList<>();
        for (EntryPoint ep : entryPoints) {
            declIndex.values().stream()
                    .filter(d -> d.file().equals(ep.file()) && d.line() == ep.line()
                            && d.method().equals(ep.method()))
                    .findFirst()
                    .ifPresent(d -> roots.add(d.id()));
        }
        roots.sort(Comparator.naturalOrder());

        Deque<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        for (String root : roots) {
            if (visited.add(root)) {
                queue.add(root);
                addNode(nodes, declIndex.get(root));
            }
        }

        while (!queue.isEmpty()) {
            String currentId = queue.poll();
            Decl current = declIndex.get(currentId);
            if (current == null || current.node() == null) {
                continue;
            }
            for (MethodCallExpr call : current.node().findAll(MethodCallExpr.class)) {
                String calleeId = resolveCallee(call);
                Decl callee = calleeId == null ? null : declIndex.get(calleeId);
                if (callee != null) {
                    addNode(nodes, callee);
                    String key = currentId + "->" + calleeId + "@" + Ast.line(call);
                    if (edgeKeys.add(key)) {
                        edges.add(new Edge(currentId, calleeId, current.file(), Ast.line(call)));
                    }
                    if (visited.add(calleeId)) {
                        queue.add(calleeId);
                    }
                    continue;
                }
                // Not an in-source method. If the receiver is an injected collaborator field whose
                // implementation lives outside the source — a Spring Data repository's inherited method
                // (save/findById), an Elasticsearch/JDBC/HTTP client — surface the infra boundary as a
                // synthetic leaf participant instead of dropping the call. Still a real AST fact: the
                // call exists; we render the edge to the collaborator's type rather than invent one.
                String infraType = injectedCollaboratorType(call, currentId, injectedFields);
                if (infraType != null) {
                    String synthId = "external:" + infraType + "#" + call.getNameAsString();
                    addExternalNode(nodes, synthId, infraType, call.getNameAsString());
                    String key = currentId + "->" + synthId + "@" + Ast.line(call);
                    if (edgeKeys.add(key)) {
                        edges.add(new Edge(currentId, synthId, current.file(), Ast.line(call)));
                    }
                }
            }
        }

        edges.sort(Comparator.comparing(Edge::from).thenComparing(Edge::to).thenComparingInt(Edge::line));
        List<AssignmentSite> sites = collectAssignmentSites(project, enumQualifiedNames);
        return new CallGraph(new ArrayList<>(nodes.values()), edges, sites);
    }

    private static void addNode(Map<String, Node> nodes, Decl d) {
        if (d != null) {
            nodes.putIfAbsent(d.id(), new Node(d.id(), d.className(), d.method(), d.file(), d.line(), d.role(), d.returnType()));
        }
    }

    /** A synthetic leaf node for an out-of-source infra collaborator (no file/line, role "external"). */
    private static void addExternalNode(Map<String, Node> nodes, String id, String type, String method) {
        nodes.putIfAbsent(id, new Node(id, type, method, "", 0, "external", ""));
    }

    /** Value/utility/JDK types that are never an infra collaborator worth drawing as a boundary. */
    private static final Set<String> VALUE_TYPES = Set.of(
            "String", "CharSequence", "Integer", "Long", "Short", "Byte", "Boolean", "Double", "Float",
            "Character", "BigDecimal", "BigInteger", "Number", "List", "Set", "Map", "Collection",
            "Optional", "Stream", "UUID", "LocalDate", "LocalDateTime", "LocalTime", "Instant",
            "Duration", "Object",
            // common JDK collection/concurrency impls held as fields — not a datastore/gateway
            "ArrayList", "LinkedList", "ArrayDeque", "Vector", "Stack", "Queue", "Deque",
            "HashMap", "LinkedHashMap", "TreeMap", "ConcurrentHashMap", "HashSet", "LinkedHashSet",
            "TreeSet", "CopyOnWriteArrayList", "CopyOnWriteArraySet", "AtomicInteger", "AtomicLong",
            "AtomicReference", "AtomicBoolean");

    /**
     * If {@code call} is a direct {@code field.method(...)} on an instance field of the caller class
     * whose type is not a plain value type, return that field type's simple name — the infra
     * collaborator to draw. Only direct field receivers are considered (not fluent builder chains or
     * locals), which is enough to surface repositories and datastore/HTTP clients cleanly.
     */
    private static String injectedCollaboratorType(MethodCallExpr call, String callerId,
                                                   Map<String, Map<String, String>> injectedFields) {
        if (call.getScope().isEmpty() || !(call.getScope().get() instanceof NameExpr recv)) {
            return null;
        }
        int hash = callerId.lastIndexOf('#');
        Map<String, String> fields = hash < 0 ? null : injectedFields.get(callerId.substring(0, hash));
        if (fields == null) {
            return null;
        }
        String type = fields.get(recv.getNameAsString());
        return type == null || VALUE_TYPES.contains(type) ? null : type;
    }

    private void indexDeclarations(ProjectModel project, Map<String, Decl> index, Set<String> enums,
                                   Map<String, Map<String, String>> injectedFields) {
        for (ProjectModel.ParsedFile pf : project.files()) {
            for (ClassOrInterfaceDeclaration type : pf.cu().findAll(ClassOrInterfaceDeclaration.class)) {
                String fqcn = type.getFullyQualifiedName().orElse(type.getNameAsString());
                String role = role(type);
                for (MethodDeclaration m : type.getMethods()) {
                    // Overloads share a name; disambiguate the 2nd+ by line so each is its own node.
                    String nameId = fqcn + "#" + m.getNameAsString();
                    String id = index.containsKey(nameId) ? nameId + "~" + Ast.line(m) : nameId;
                    index.putIfAbsent(id, new Decl(id, type.getNameAsString(), m.getNameAsString(),
                            pf.relPath(), Ast.line(m), role, m.getType().asString(), m));
                }
                // Instance fields = candidate injected collaborators (repositories, clients, gateways).
                Map<String, String> fields = new HashMap<>();
                for (var field : type.getFields()) {
                    if (field.isStatic()) {
                        continue;
                    }
                    for (var v : field.getVariables()) {
                        fields.put(v.getNameAsString(), simpleTypeName(v.getType().asString()));
                    }
                }
                if (!fields.isEmpty()) {
                    injectedFields.put(fqcn, fields);
                }
            }
            for (EnumDeclaration e : pf.cu().findAll(EnumDeclaration.class)) {
                e.getFullyQualifiedName().ifPresent(enums::add);
                // Index enum methods too, so a call to a project enum's own method resolves to an
                // in-source edge instead of being mistaken for an external infra collaborator.
                String fqcn = e.getFullyQualifiedName().orElse(e.getNameAsString());
                for (MethodDeclaration m : e.getMethods()) {
                    String id = fqcn + "#" + m.getNameAsString();
                    index.putIfAbsent(id, new Decl(id, e.getNameAsString(), m.getNameAsString(),
                            pf.relPath(), Ast.line(m), "domain", m.getType().asString(), m));
                }
            }
        }
    }

    /** Simple type name: drop generics, package, and array brackets ({@code a.b.Foo<X>[]} → {@code Foo}). */
    private static String simpleTypeName(String type) {
        String t = type;
        int lt = t.indexOf('<');
        if (lt >= 0) {
            t = t.substring(0, lt);
        }
        int dot = t.lastIndexOf('.');
        if (dot >= 0) {
            t = t.substring(dot + 1);
        }
        return t.replace("[]", "").trim();
    }

    private static String role(ClassOrInterfaceDeclaration type) {
        String n = type.getNameAsString();
        if (Ast.has(type, "RestController") || Ast.has(type, "Controller") || n.endsWith("Controller")) return "controller";
        if (Ast.has(type, "Service") || n.endsWith("Service") || n.endsWith("Factory")) return "service";
        if (Ast.has(type, "Repository") || n.endsWith("Repository")) return "repository";
        if (Ast.has(type, "Entity") || Ast.has(type, "Embeddable")) return "domain";
        return "other";
    }

    /**
     * Resolve a call to {@code qualifiedClass#method}. First try full symbol resolution (precise,
     * overload-aware). If that fails — common when an argument expression can't be resolved to
     * disambiguate an overload — fall back to resolving just the receiver's type (or the enclosing
     * type for an unscoped call) and keying by method name. The caller keeps only ids that match a
     * real project declaration, so the fallback can recover true edges but never fabricate a target.
     */
    private static String resolveCallee(MethodCallExpr call) {
        try {
            var resolved = call.resolve();
            return resolved.declaringType().getQualifiedName() + "#" + resolved.getName();
        } catch (Exception | StackOverflowError precise) {
            // fall through to the receiver-type fallback
        }
        try {
            if (call.getScope().isPresent()) {
                ResolvedType t = call.getScope().get().calculateResolvedType();
                if (t.isReferenceType()) {
                    return t.asReferenceType().getQualifiedName() + "#" + call.getNameAsString();
                }
            } else {
                var owner = call.findAncestor(com.github.javaparser.ast.body.TypeDeclaration.class);
                if (owner.isPresent()) {
                    var fqn = owner.get().getFullyQualifiedName();
                    if (fqn.isPresent()) {
                        return fqn.get() + "#" + call.getNameAsString();
                    }
                }
            }
        } catch (Exception | StackOverflowError fallback) {
            return null;
        }
        return null;
    }

    private List<AssignmentSite> collectAssignmentSites(ProjectModel project, Set<String> enumQualifiedNames) {
        List<AssignmentSite> sites = new ArrayList<>();
        for (ProjectModel.ParsedFile pf : project.files()) {
            for (AssignExpr assign : pf.cu().findAll(AssignExpr.class)) {
                String enumType = enumTypeOf(assign.getTarget(), enumQualifiedNames);
                if (enumType == null) {
                    continue;
                }
                MethodDeclaration method = assign.findAncestor(MethodDeclaration.class).orElse(null);
                ClassOrInterfaceDeclaration cls = assign.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
                sites.add(new AssignmentSite(
                        enumType,
                        fieldName(assign.getTarget()),
                        constantOf(assign.getValue()),
                        cls == null ? null : cls.getNameAsString(),
                        method == null ? null : method.getNameAsString(),
                        pf.relPath(),
                        Ast.line(assign)));
            }
        }
        sites.sort(Comparator.comparing(AssignmentSite::enumType)
                .thenComparing(AssignmentSite::file)
                .thenComparingInt(AssignmentSite::line));
        return sites;
    }

    /** Simple name of the target's enum type if it resolves to a project enum, else null. */
    private static String enumTypeOf(Expression target, Set<String> enumQualifiedNames) {
        try {
            ResolvedType t = target.calculateResolvedType();
            if (!t.isReferenceType()) {
                return null;
            }
            String qn = t.asReferenceType().getQualifiedName();
            if (enumQualifiedNames.contains(qn)) {
                int dot = qn.lastIndexOf('.');
                return dot >= 0 ? qn.substring(dot + 1) : qn;
            }
            return null;
        } catch (Exception | StackOverflowError e) {
            return null;
        }
    }

    private static String fieldName(Expression target) {
        if (target instanceof FieldAccessExpr fa) return fa.getNameAsString();
        if (target instanceof NameExpr ne) return ne.getNameAsString();
        return target.toString();
    }

    /** Enum constant name from an {@code Enum.CONSTANT} / {@code CONSTANT} RHS, else source text. */
    private static String constantOf(Expression value) {
        if (value instanceof FieldAccessExpr fa) return fa.getNameAsString();
        if (value instanceof NameExpr ne) return ne.getNameAsString();
        return value.toString();
    }
}
