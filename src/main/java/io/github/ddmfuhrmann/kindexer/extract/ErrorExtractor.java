package io.github.ddmfuhrmann.kindexer.extract;

import io.github.ddmfuhrmann.kindexer.ast.ProjectModel;
import io.github.ddmfuhrmann.kindexer.model.Flows.ExceptionStatus;
import io.github.ddmfuhrmann.kindexer.model.Flows.ThrowSite;
import io.github.ddmfuhrmann.kindexer.util.Ast;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.IfStmt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Extractor — the alternative (error) flows. Two deterministic products:
 * <ul>
 *   <li>{@link ThrowSite}s: every {@code new *Exception(...)} construction (covers both
 *       {@code throw new X} and {@code orElseThrow(() -> new X)}), with its enclosing guard
 *       condition and message. These are the 4xx/409 branches of a flow.
 *   <li>{@link ExceptionStatus}: exception → HTTP status, read from a {@code @ControllerAdvice}
 *       handler ({@code @ExceptionHandler} + {@code @ResponseStatus}) or a {@code @ResponseStatus}
 *       on the exception class itself.
 * </ul>
 */
public final class ErrorExtractor {

    private static final Map<String, String> STATUS_CODES = Map.ofEntries(
            Map.entry("OK", "200"), Map.entry("CREATED", "201"), Map.entry("NO_CONTENT", "204"),
            Map.entry("BAD_REQUEST", "400"), Map.entry("UNAUTHORIZED", "401"),
            Map.entry("FORBIDDEN", "403"), Map.entry("NOT_FOUND", "404"),
            Map.entry("CONFLICT", "409"), Map.entry("UNPROCESSABLE_ENTITY", "422"),
            Map.entry("INTERNAL_SERVER_ERROR", "500"));

    public List<ThrowSite> throwSites(ProjectModel project) {
        List<ThrowSite> out = new ArrayList<>();
        for (ProjectModel.ParsedFile pf : project.files()) {
            if (pf.relPath().contains("src/test/")) {
                continue; // production error paths only — a test's own throws are not a flow branch
            }
            for (ClassOrInterfaceDeclaration type : pf.cu().findAll(ClassOrInterfaceDeclaration.class)) {
                String fqcn = type.getFullyQualifiedName().orElse(type.getNameAsString());
                for (MethodDeclaration m : type.getMethods()) {
                    for (ObjectCreationExpr oce : m.findAll(ObjectCreationExpr.class)) {
                        String ex = oce.getType().getNameAsString();
                        if (!ex.endsWith("Exception")) {
                            continue;
                        }
                        out.add(new ThrowSite(
                                ex,
                                messageOf(oce),
                                conditionOf(oce),
                                type.getNameAsString(),
                                m.getNameAsString(),
                                fqcn + "#" + m.getNameAsString(),
                                pf.relPath(),
                                Ast.line(oce)));
                    }
                }
            }
        }
        out.sort(Comparator.comparing(ThrowSite::nodeId)
                .thenComparing(ThrowSite::exceptionType)
                .thenComparingInt(ThrowSite::line));
        return out;
    }

    public List<ExceptionStatus> exceptionStatuses(ProjectModel project) {
        List<ExceptionStatus> out = new ArrayList<>();
        for (ProjectModel.ParsedFile pf : project.files()) {
            for (ClassOrInterfaceDeclaration type : pf.cu().findAll(ClassOrInterfaceDeclaration.class)) {
                boolean advice = Ast.has(type, "ControllerAdvice") || Ast.has(type, "RestControllerAdvice");
                if (advice) {
                    for (MethodDeclaration m : type.getMethods()) {
                        AnnotationExpr handler = m.getAnnotationByName("ExceptionHandler").orElse(null);
                        if (handler == null) {
                            continue;
                        }
                        String status = statusOf(m.getAnnotationByName("ResponseStatus").orElse(null));
                        for (String ex : handledTypes(handler, m)) {
                            out.add(new ExceptionStatus(ex, status, type.getNameAsString()));
                        }
                    }
                }
                // @ResponseStatus directly on an exception class.
                if (type.getNameAsString().endsWith("Exception")) {
                    type.getAnnotationByName("ResponseStatus").ifPresent(rs ->
                            out.add(new ExceptionStatus(type.getNameAsString(), statusOf(rs), type.getNameAsString())));
                }
            }
        }
        out.sort(Comparator.comparing(ExceptionStatus::exceptionType));
        return out;
    }

    private static String messageOf(ObjectCreationExpr oce) {
        if (oce.getArguments().isEmpty()) {
            return null;
        }
        return literalOf(oce.getArguments().get(0));
    }

    /** First string literal in an expression (unwrapping a {@code "msg" + id} concatenation). */
    private static String literalOf(Expression e) {
        if (e instanceof StringLiteralExpr s) {
            return s.getValue();
        }
        if (e instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.PLUS) {
            String left = literalOf(b.getLeft());
            return left != null ? left : literalOf(b.getRight());
        }
        return null;
    }

    private static String conditionOf(ObjectCreationExpr oce) {
        return oce.findAncestor(IfStmt.class)
                .map(f -> f.getCondition().toString())
                .orElse(null);
    }

    private static List<String> handledTypes(AnnotationExpr handler, MethodDeclaration method) {
        List<String> types = new ArrayList<>();
        Expression value = Ast.memberExpr(handler, "value");
        if (value == null && handler.isSingleMemberAnnotationExpr()) {
            value = handler.asSingleMemberAnnotationExpr().getMemberValue();
        }
        if (value instanceof ArrayInitializerExpr arr) {
            for (Expression el : arr.getValues()) {
                if (el instanceof ClassExpr c) {
                    types.add(c.getType().asString());
                }
            }
        } else if (value instanceof ClassExpr c) {
            types.add(c.getType().asString());
        }
        if (types.isEmpty()) {
            // Fall back to the handler's exception parameter type.
            method.getParameters().stream()
                    .map(p -> p.getType().asString())
                    .filter(t -> t.endsWith("Exception"))
                    .forEach(types::add);
        }
        return types;
    }

    private static String statusOf(AnnotationExpr responseStatus) {
        if (responseStatus == null) {
            return "—";
        }
        String raw = Ast.member(responseStatus, "value");
        if (raw == null) {
            raw = Ast.member(responseStatus, "code");
        }
        if (raw == null) {
            raw = Ast.stringValue(responseStatus);
        }
        if (raw == null) {
            return "—";
        }
        int dot = raw.lastIndexOf('.');
        String name = dot >= 0 ? raw.substring(dot + 1) : raw;
        String code = STATUS_CODES.get(name);
        return code != null ? code + " " + name : name;
    }
}
