package io.github.ddmfuhrmann.kindexer.extract;

import io.github.ddmfuhrmann.kindexer.ast.ProjectModel;
import io.github.ddmfuhrmann.kindexer.model.TestScenarios.Scenario;
import io.github.ddmfuhrmann.kindexer.model.TestScenarios.TestUnit;
import io.github.ddmfuhrmann.kindexer.util.Ast;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Extractor 5 — behaviours per test unit. JUnit 5 {@code @Test} methods (including those in
 * {@code @Nested} classes); {@code @DisplayName} used verbatim when present, otherwise the method
 * name is humanized. The test→target link is by naming convention (strip Test/Tests/IT/ITCase) and
 * flagged {@code targetCertain} only when a matching project type actually exists.
 */
public final class TestScenarioExtractor {

    public List<TestUnit> extract(ProjectModel project) {
        Set<String> mainTypes = collectMainTypeNames(project);
        List<TestUnit> out = new ArrayList<>();

        for (ProjectModel.ParsedFile pf : project.files()) {
            if (!pf.relPath().contains("src/test/")) {
                continue;
            }
            for (ClassOrInterfaceDeclaration type : pf.cu().findAll(ClassOrInterfaceDeclaration.class)) {
                List<Scenario> scenarios = new ArrayList<>();
                for (MethodDeclaration m : type.findAll(MethodDeclaration.class)) {
                    if (!Ast.has(m, "Test")) {
                        continue;
                    }
                    String display = m.getAnnotationByName("DisplayName").map(Ast::stringValue).orElse(null);
                    String description = display != null ? display : humanize(m.getNameAsString());
                    scenarios.add(new Scenario(m.getNameAsString(), display, description, Ast.line(m)));
                }
                if (scenarios.isEmpty()) {
                    continue;
                }
                scenarios.sort(Comparator.comparingInt(Scenario::line));
                String testClass = type.getNameAsString();
                String target = stripTestSuffix(testClass);
                boolean certain = target != null && mainTypes.contains(target);
                out.add(new TestUnit(testClass, pf.relPath(), target, certain, scenarios));
            }
        }
        out.sort(Comparator.comparing(TestUnit::testClass));
        return out;
    }

    private static Set<String> collectMainTypeNames(ProjectModel project) {
        Set<String> names = new HashSet<>();
        for (ProjectModel.ParsedFile pf : project.files()) {
            if (!pf.relPath().contains("src/main/")) {
                continue;
            }
            pf.cu().findAll(ClassOrInterfaceDeclaration.class)
                    .forEach(t -> names.add(t.getNameAsString()));
        }
        return names;
    }

    private static String stripTestSuffix(String testClass) {
        for (String suffix : List.of("ITCase", "Tests", "Test", "IT")) {
            if (testClass.endsWith(suffix) && testClass.length() > suffix.length()) {
                return testClass.substring(0, testClass.length() - suffix.length());
            }
        }
        return null;
    }

    /** {@code findAll_noFilter_ok} → {@code "find all no filter ok"}. */
    static String humanize(String method) {
        String spaced = method
                .replace('_', ' ')
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        return spaced.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
