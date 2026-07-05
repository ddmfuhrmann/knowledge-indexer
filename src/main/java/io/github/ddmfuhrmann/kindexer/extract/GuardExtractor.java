package io.github.ddmfuhrmann.kindexer.extract;

import io.github.ddmfuhrmann.kindexer.ast.ProjectModel;
import io.github.ddmfuhrmann.kindexer.model.Flows.GuardCheck;
import io.github.ddmfuhrmann.kindexer.util.Ast;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extractor — imperative guards/assertions, read at the call site. A guard's own body only shows a
 * generic condition ({@code value == null}); the call site names the actual field
 * ({@code Guard.requireNonNull(customerId, "customerId")}). Recognizes the project's {@code Guard}
 * plus common library validators ({@code Objects.requireNonNull}, Spring {@code Assert.*}, Guava
 * {@code Preconditions.*}, Apache {@code Validate.*}). Main sources only.
 */
public final class GuardExtractor {

    private static final Set<String> GUARD_SCOPES =
            Set.of("Guard", "Objects", "Assert", "Preconditions", "Validate");

    private static final Map<String, String> GUARD_METHODS = Map.ofEntries(
            Map.entry("requireNonNull", "required"),
            Map.entry("requireText", "required text"),
            Map.entry("requireNonBlank", "required text"),
            Map.entry("requirePositive", "must be > 0"),
            Map.entry("requireNonNegative", "must be ≥ 0"),
            Map.entry("notNull", "required"),
            Map.entry("notEmpty", "must not be empty"),
            Map.entry("hasText", "required text"),
            Map.entry("hasLength", "required text"),
            Map.entry("isTrue", "must hold"),
            Map.entry("state", "must hold"),
            Map.entry("checkNotNull", "required"),
            Map.entry("checkArgument", "must hold"),
            Map.entry("checkState", "must hold"));

    public List<GuardCheck> extract(ProjectModel project) {
        List<GuardCheck> out = new ArrayList<>();
        for (ProjectModel.ParsedFile pf : project.files()) {
            if (pf.relPath().contains("src/test/")) {
                continue;
            }
            for (ClassOrInterfaceDeclaration type : pf.cu().findAll(ClassOrInterfaceDeclaration.class)) {
                String fqcn = type.getFullyQualifiedName().orElse(type.getNameAsString());
                for (MethodDeclaration m : type.getMethods()) {
                    for (MethodCallExpr call : m.findAll(MethodCallExpr.class)) {
                        String constraint = guardConstraint(call);
                        if (constraint == null || call.getArguments().isEmpty()) {
                            continue;
                        }
                        out.add(new GuardCheck(
                                fieldOf(call.getArgument(0)),
                                constraint,
                                messageOf(call),
                                type.getNameAsString(),
                                m.getNameAsString(),
                                fqcn + "#" + m.getNameAsString(),
                                pf.relPath(),
                                Ast.line(call)));
                    }
                }
            }
        }
        out.sort(Comparator.comparing(GuardCheck::nodeId)
                .thenComparing(GuardCheck::field)
                .thenComparingInt(GuardCheck::line));
        return out;
    }

    /** Normalized constraint if this call is a recognized guard on a recognized holder, else null. */
    private static String guardConstraint(MethodCallExpr call) {
        String method = call.getNameAsString();
        if (!GUARD_METHODS.containsKey(method)) {
            return null;
        }
        // Require an explicit holder scope (Guard/Objects/Assert/…) to avoid matching unrelated methods.
        return call.getScope()
                .filter(s -> s instanceof NameExpr n && GUARD_SCOPES.contains(n.getNameAsString()))
                .map(s -> GUARD_METHODS.get(method))
                .orElse(null);
    }

    /** The guarded value, simplified: {@code this.date}→date, {@code cmd.customerId()}→customerId(). */
    private static String fieldOf(Expression arg) {
        if (arg instanceof FieldAccessExpr fa) {
            return fa.getNameAsString();
        }
        if (arg instanceof NameExpr ne) {
            return ne.getNameAsString();
        }
        if (arg instanceof MethodCallExpr mc) {
            return mc.getNameAsString() + "()";
        }
        String s = arg.toString();
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    private static String messageOf(MethodCallExpr call) {
        for (Expression a : call.getArguments()) {
            if (a instanceof StringLiteralExpr s) {
                return s.getValue();
            }
        }
        return null;
    }
}
