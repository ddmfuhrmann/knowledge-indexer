package io.github.ddmfuhrmann.kindexer.extract;

import io.github.ddmfuhrmann.kindexer.ast.ProjectModel;
import io.github.ddmfuhrmann.kindexer.model.CallGraph;
import io.github.ddmfuhrmann.kindexer.model.CallGraph.AssignmentSite;
import io.github.ddmfuhrmann.kindexer.model.StateMachines.StateMachine;
import io.github.ddmfuhrmann.kindexer.util.Ast;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Extractor 4 — the deterministic half of state machines. Heuristic for "status enum": its name
 * contains Status/State, or an entity field named status/state is typed by it. Emits the enum's
 * declared states plus the assignment sites already found in the call graph. It does <b>not</b>
 * name transitions — that is enrichment. Empty output is correct when no such enum exists.
 */
public final class StateMachineExtractor {

    public List<StateMachine> extract(ProjectModel project, CallGraph callGraph) {
        Set<String> statusEnumNames = discoverStatusEnums(project);
        List<StateMachine> out = new ArrayList<>();

        for (ProjectModel.ParsedFile pf : project.files()) {
            for (EnumDeclaration e : pf.cu().findAll(EnumDeclaration.class)) {
                String name = e.getNameAsString();
                if (!statusEnumNames.contains(name)) {
                    continue;
                }
                List<String> states = e.getEntries().stream()
                        .map(EnumConstantDeclaration::getNameAsString)
                        .sorted()
                        .toList();
                List<AssignmentSite> sites = callGraph.assignmentSites().stream()
                        .filter(s -> name.equals(s.enumType()))
                        .toList();
                out.add(new StateMachine(name, pf.relPath(), Ast.line(e), states, sites));
            }
        }
        out.sort(Comparator.comparing(StateMachine::enumType));
        return out;
    }

    private Set<String> discoverStatusEnums(ProjectModel project) {
        Set<String> enumNames = new TreeSet<>();
        Set<String> byField = new TreeSet<>();

        for (ProjectModel.ParsedFile pf : project.files()) {
            for (EnumDeclaration e : pf.cu().findAll(EnumDeclaration.class)) {
                enumNames.add(e.getNameAsString());
            }
            // An entity field literally named status/state points at its enum type.
            for (ClassOrInterfaceDeclaration type : pf.cu().findAll(ClassOrInterfaceDeclaration.class)) {
                for (FieldDeclaration f : type.getFields()) {
                    f.getVariables().forEach(v -> {
                        String fn = v.getNameAsString().toLowerCase(Locale.ROOT);
                        if (fn.equals("status") || fn.equals("state")) {
                            byField.add(v.getType().asString());
                        }
                    });
                }
            }
        }

        Set<String> result = new TreeSet<>();
        for (String name : enumNames) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.contains("status") || lower.contains("state") || byField.contains(name)) {
                result.add(name);
            }
        }
        return result;
    }
}
