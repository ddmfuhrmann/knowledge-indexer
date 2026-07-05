package io.github.ddmfuhrmann.kindexer.extract;

import io.github.ddmfuhrmann.kindexer.ast.ProjectModel;
import io.github.ddmfuhrmann.kindexer.model.EntryPoints.EntryPoint;
import io.github.ddmfuhrmann.kindexer.model.Flows.InputConstraint;
import io.github.ddmfuhrmann.kindexer.util.Ast;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extractor — validation preconditions. For each HTTP entry point, resolves the {@code @RequestBody}
 * command type and reads the Bean Validation constraints on its fields/record components. These are
 * the input contract of the flow (the {@code 400} branch), read straight from the annotations.
 */
public final class InputConstraintExtractor {

    private static final Set<String> BEAN_VALIDATION = Set.of(
            "NotNull", "NotEmpty", "NotBlank", "Size", "Min", "Max", "Positive", "PositiveOrZero",
            "Negative", "NegativeOrZero", "Email", "Pattern", "Past", "PastOrPresent", "Future",
            "FutureOrPresent", "DecimalMin", "DecimalMax", "Digits", "AssertTrue", "AssertFalse");

    public List<InputConstraint> extract(ProjectModel project, List<EntryPoint> entryPoints) {
        Map<String, ClassOrInterfaceDeclaration> controllers = new HashMap<>();
        Map<String, TypeDeclaration<?>> typesByName = new HashMap<>();
        for (ProjectModel.ParsedFile pf : project.files()) {
            for (TypeDeclaration<?> t : pf.cu().findAll(TypeDeclaration.class)) {
                typesByName.putIfAbsent(t.getNameAsString(), t);
                if (t instanceof ClassOrInterfaceDeclaration c) {
                    controllers.putIfAbsent(c.getNameAsString(), c);
                }
            }
        }

        List<InputConstraint> out = new ArrayList<>();
        for (EntryPoint ep : entryPoints) {
            if (!"http".equals(ep.category())) {
                continue;
            }
            ClassOrInterfaceDeclaration controller = controllers.get(ep.className());
            if (controller == null) {
                continue;
            }
            controller.getMethodsByName(ep.method()).stream().findFirst().ifPresent(method -> {
                String commandType = requestBodyType(method);
                if (commandType == null) {
                    return;
                }
                TypeDeclaration<?> command = typesByName.get(commandType);
                if (command != null) {
                    collectConstraints(command, ep.id(), commandType, out);
                }
            });
        }
        out.sort(Comparator.comparing(InputConstraint::entryPointId)
                .thenComparing(InputConstraint::field)
                .thenComparing(InputConstraint::constraint));
        return out;
    }

    private static String requestBodyType(MethodDeclaration method) {
        for (Parameter p : method.getParameters()) {
            if (Ast.has(p, "RequestBody")) {
                return p.getType().asString();
            }
        }
        return null;
    }

    private void collectConstraints(TypeDeclaration<?> command, String epId, String commandType, List<InputConstraint> out) {
        if (command instanceof RecordDeclaration record) {
            for (Parameter component : record.getParameters()) {
                addConstraints(component, component.getNameAsString(), epId, commandType, out);
            }
        } else if (command instanceof ClassOrInterfaceDeclaration cls) {
            cls.getFields().forEach(f -> f.getVariables().forEach(v ->
                    addConstraints(f, v.getNameAsString(), epId, commandType, out)));
        }
    }

    private void addConstraints(NodeWithAnnotations<?> node, String field, String epId, String commandType, List<InputConstraint> out) {
        for (AnnotationExpr ann : node.getAnnotations()) {
            String name = ann.getNameAsString();
            if (BEAN_VALIDATION.contains(name)) {
                out.add(new InputConstraint(epId, commandType, field, name, detailOf(ann)));
            }
        }
    }

    private static String detailOf(AnnotationExpr ann) {
        if (ann instanceof NormalAnnotationExpr n && !n.getPairs().isEmpty()) {
            List<String> parts = new ArrayList<>();
            n.getPairs().forEach(p -> parts.add(p.getNameAsString() + "=" + p.getValue()));
            return String.join(", ", parts);
        }
        return null;
    }
}
