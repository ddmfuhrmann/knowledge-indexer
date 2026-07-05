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
        indexDeclarations(project, declIndex, enumQualifiedNames);

        Map<String, Node> nodes = new TreeMap<>();
        Set<String> edgeKeys = new HashSet<>();
        List<Edge> edges = new ArrayList<>();

        // Roots: entry-point methods that map to an indexed declaration.
        List<String> roots = new ArrayList<>();
        for (EntryPoint ep : entryPoints) {
            declIndex.values().stream()
                    .filter(d -> d.className().equals(ep.className()) && d.method().equals(ep.method()))
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
                if (calleeId == null) {
                    continue; // external or unresolved — deliberately excluded
                }
                Decl callee = declIndex.get(calleeId);
                if (callee == null) {
                    continue; // resolved, but not a project method
                }
                addNode(nodes, callee);
                String key = currentId + "->" + calleeId + "@" + Ast.line(call);
                if (edgeKeys.add(key)) {
                    edges.add(new Edge(currentId, calleeId, current.file(), Ast.line(call)));
                }
                if (visited.add(calleeId)) {
                    queue.add(calleeId);
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

    private void indexDeclarations(ProjectModel project, Map<String, Decl> index, Set<String> enums) {
        for (ProjectModel.ParsedFile pf : project.files()) {
            for (ClassOrInterfaceDeclaration type : pf.cu().findAll(ClassOrInterfaceDeclaration.class)) {
                String fqcn = type.getFullyQualifiedName().orElse(type.getNameAsString());
                String role = role(type);
                for (MethodDeclaration m : type.getMethods()) {
                    String id = fqcn + "#" + m.getNameAsString();
                    index.putIfAbsent(id, new Decl(id, type.getNameAsString(), m.getNameAsString(),
                            pf.relPath(), Ast.line(m), role, m.getType().asString(), m));
                }
            }
            for (EnumDeclaration e : pf.cu().findAll(EnumDeclaration.class)) {
                e.getFullyQualifiedName().ifPresent(enums::add);
            }
        }
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
