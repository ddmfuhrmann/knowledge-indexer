package io.github.ddmfuhrmann.kindexer.extract;

import io.github.ddmfuhrmann.kindexer.ast.ProjectModel;
import io.github.ddmfuhrmann.kindexer.model.EntryPoints.EntryPoint;
import io.github.ddmfuhrmann.kindexer.util.Ast;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extractor 2 — entry points grouped by category (http, scheduled, event, cli). Each entry records
 * the class/method it originates from, so it can double as a call-graph root and as an evidence
 * anchor for coverage enrichment.
 */
public final class EntryPointExtractor {

    private static final Map<String, String> HTTP_MAPPINGS = new LinkedHashMap<>();
    static {
        HTTP_MAPPINGS.put("GetMapping", "GET");
        HTTP_MAPPINGS.put("PostMapping", "POST");
        HTTP_MAPPINGS.put("PutMapping", "PUT");
        HTTP_MAPPINGS.put("DeleteMapping", "DELETE");
        HTTP_MAPPINGS.put("PatchMapping", "PATCH");
    }

    private static final List<String> EVENT_ANNOTATIONS =
            List.of("KafkaListener", "RabbitListener", "SqsListener", "EventListener");

    public List<EntryPoint> extract(ProjectModel project) {
        List<EntryPoint> out = new ArrayList<>();
        for (ProjectModel.ParsedFile pf : project.files()) {
            for (ClassOrInterfaceDeclaration type :
                    pf.cu().findAll(ClassOrInterfaceDeclaration.class)) {
                String cls = type.getNameAsString();
                boolean http = Ast.has(type, "RestController") || Ast.has(type, "Controller");
                String basePath = http ? mappingPath(type.getAnnotationByName("RequestMapping").orElse(null)) : "";
                boolean cliRunner = implementsAny(type, "CommandLineRunner", "ApplicationRunner");

                for (MethodDeclaration m : type.getMethods()) {
                    if (http) {
                        addHttp(out, m, cls, basePath, pf.relPath());
                    }
                    addScheduled(out, m, cls, pf.relPath());
                    addEvent(out, m, cls, pf.relPath());
                }
                if (cliRunner) {
                    type.getMethodsByName("run").forEach(m -> out.add(new EntryPoint(
                            "cli", "cli:" + cls, null, null, null, null,
                            cls, "run", pf.relPath(), Ast.line(m))));
                }
            }
        }
        out.sort(Comparator.comparing(EntryPoint::category).thenComparing(EntryPoint::id));
        return out;
    }

    private void addHttp(List<EntryPoint> out, MethodDeclaration m, String cls, String basePath, String file) {
        for (Map.Entry<String, String> e : HTTP_MAPPINGS.entrySet()) {
            m.getAnnotationByName(e.getKey()).ifPresent(ann -> {
                String path = join(basePath, Ast.stringValue(ann));
                out.add(new EntryPoint("http", "http:" + e.getValue() + " " + path,
                        e.getValue(), path, null, null, cls, m.getNameAsString(), file, Ast.line(m)));
            });
        }
        m.getAnnotationByName("RequestMapping").ifPresent(ann -> {
            String path = join(basePath, mappingPath(ann));
            String verb = requestMethod(ann);
            out.add(new EntryPoint("http", "http:" + verb + " " + path,
                    verb, path, null, null, cls, m.getNameAsString(), file, Ast.line(m)));
        });
    }

    private void addScheduled(List<EntryPoint> out, MethodDeclaration m, String cls, String file) {
        m.getAnnotationByName("Scheduled").ifPresent(ann -> {
            String cron = Ast.member(ann, "cron");
            if (cron == null) {
                String fixed = Ast.member(ann, "fixedRate");
                cron = fixed == null ? "fixedDelay" : "fixedRate=" + fixed;
            }
            out.add(new EntryPoint("scheduled", "scheduled:" + cls + "#" + m.getNameAsString(),
                    null, null, cron, null, cls, m.getNameAsString(), file, Ast.line(m)));
        });
    }

    private void addEvent(List<EntryPoint> out, MethodDeclaration m, String cls, String file) {
        for (String annName : EVENT_ANNOTATIONS) {
            m.getAnnotationByName(annName).ifPresent(ann -> {
                String dest = firstNonNull(Ast.member(ann, "topics"),
                        Ast.member(ann, "queues"), Ast.stringValue(ann));
                out.add(new EntryPoint("event", "event:" + cls + "#" + m.getNameAsString(),
                        null, null, null, dest, cls, m.getNameAsString(), file, Ast.line(m)));
            });
        }
    }

    private static String mappingPath(AnnotationExpr ann) {
        if (ann == null) {
            return "";
        }
        String v = Ast.stringValue(ann);
        if (v == null) {
            v = Ast.member(ann, "path");
        }
        return v == null ? "" : v;
    }

    private static String requestMethod(AnnotationExpr ann) {
        String m = Ast.member(ann, "method");
        if (m == null) {
            return "ANY";
        }
        int dot = m.lastIndexOf('.');
        return dot >= 0 ? m.substring(dot + 1) : m;
    }

    private static boolean implementsAny(ClassOrInterfaceDeclaration type, String... names) {
        return type.getImplementedTypes().stream()
                .anyMatch(t -> {
                    for (String n : names) {
                        if (t.getNameAsString().equals(n)) return true;
                    }
                    return false;
                });
    }

    private static String join(String base, String path) {
        String b = base == null ? "" : base;
        String p = path == null ? "" : path;
        if (b.isEmpty()) return p.isEmpty() ? "/" : p;
        if (p.isEmpty()) return b;
        String left = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        String right = p.startsWith("/") ? p : "/" + p;
        return left + right;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) {
            if (v != null) return v;
        }
        return null;
    }
}
