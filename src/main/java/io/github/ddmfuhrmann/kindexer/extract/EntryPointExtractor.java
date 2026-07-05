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
import java.util.Set;

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

    /** In-process listeners (Spring / Spring Modulith) — trigger is the payload (first param) type. */
    private static final List<String> INPROCESS_LISTENERS =
            List.of("ApplicationModuleListener", "TransactionalEventListener", "EventListener");

    /** Broker / message-driven listeners — trigger is a topic/queue/channel; annotation → attribute. */
    private static final Map<String, String> BROKER_LISTENERS = new LinkedHashMap<>();
    static {
        BROKER_LISTENERS.put("KafkaListener", "topics");
        BROKER_LISTENERS.put("RabbitListener", "queues");
        BROKER_LISTENERS.put("JmsListener", "destination");
        BROKER_LISTENERS.put("SqsListener", null);        // value()
        BROKER_LISTENERS.put("ServiceActivator", "inputChannel");
    }

    /** Listener kinds whose delivery is asynchronous / after-commit (drawn with an async entry arrow). */
    private static final Set<String> ASYNC_LISTENERS = Set.of("ApplicationModuleListener");

    public List<EntryPoint> extract(ProjectModel project) {
        List<EntryPoint> out = new ArrayList<>();
        for (ProjectModel.ParsedFile pf : project.files()) {
            if (isTestSource(pf.relPath())) {
                continue; // entry points are the app's own surface, never test fixtures/doubles
            }
            for (ClassOrInterfaceDeclaration type :
                    pf.cu().findAll(ClassOrInterfaceDeclaration.class)) {
                String cls = type.getNameAsString();
                String fqcn = type.getFullyQualifiedName().orElse(cls);
                boolean http = Ast.has(type, "RestController") || Ast.has(type, "Controller");
                String basePath = http ? mappingPath(type.getAnnotationByName("RequestMapping").orElse(null)) : "";
                boolean cliRunner = implementsAny(type, "CommandLineRunner", "ApplicationRunner");

                for (MethodDeclaration m : type.getMethods()) {
                    if (http) {
                        addHttp(out, m, cls, basePath, pf.relPath());
                    }
                    addScheduled(out, m, cls, pf.relPath());
                    addEvent(out, m, cls, fqcn, pf.relPath());
                    addFunctionalConsumer(out, m, cls, fqcn, pf.relPath());
                }
                if (cliRunner) {
                    type.getMethodsByName("run").forEach(m -> out.add(new EntryPoint(
                            "cli", "cli:" + cls, null, null, null, null,
                            cls, "run", pf.relPath(), Ast.line(m), false)));
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
                        e.getValue(), path, null, null, cls, m.getNameAsString(), file, Ast.line(m), false));
            });
        }
        m.getAnnotationByName("RequestMapping").ifPresent(ann -> {
            String path = join(basePath, mappingPath(ann));
            String verb = requestMethod(ann);
            out.add(new EntryPoint("http", "http:" + verb + " " + path,
                    verb, path, null, null, cls, m.getNameAsString(), file, Ast.line(m), false));
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
                    null, null, cron, null, cls, m.getNameAsString(), file, Ast.line(m), false));
        });
    }

    /**
     * Event/message consumers as entry points. An arriving event roots a flow exactly like an HTTP
     * request: in-process Spring / Spring Modulith listeners (trigger = the payload type) and broker
     * listeners (trigger = topic/queue/channel). One entry per method (first matching annotation wins).
     */
    private void addEvent(List<EntryPoint> out, MethodDeclaration m, String cls, String fqcn, String file) {
        for (String ann : INPROCESS_LISTENERS) {
            var a = m.getAnnotationByName(ann);
            if (a.isPresent()) {
                String dest = firstNonNull(firstParamType(m), stripClass(Ast.member(a.get(), "classes")));
                boolean async = ASYNC_LISTENERS.contains(ann) || m.getAnnotationByName("Async").isPresent();
                out.add(eventEntry(cls, fqcn, m, file, dest, async));
                return;
            }
        }
        for (Map.Entry<String, String> e : BROKER_LISTENERS.entrySet()) {
            var a = m.getAnnotationByName(e.getKey());
            if (a.isPresent()) {
                String dest = e.getValue() == null ? Ast.stringValue(a.get())
                        : firstNonNull(Ast.member(a.get(), e.getValue()), Ast.stringValue(a.get()));
                out.add(eventEntry(cls, fqcn, m, file, firstNonNull(dest, firstParamType(m)), true));
                return;
            }
        }
    }

    /**
     * Functional-style consumers (Spring Cloud Function / Stream): a {@code @Bean} method returning
     * {@code Consumer<T>} or {@code Function<T,R>}. The bean method roots the flow (calls inside the
     * returned lambda are traced from it); the payload {@code T} is the trigger.
     */
    private void addFunctionalConsumer(List<EntryPoint> out, MethodDeclaration m, String cls, String fqcn, String file) {
        if (m.getAnnotationByName("Bean").isEmpty()) {
            return;
        }
        String ret = m.getType().asString();
        String base = simpleName(ret.contains("<") ? ret.substring(0, ret.indexOf('<')) : ret);
        if (!base.equals("Consumer") && !base.equals("Function")) {
            return;
        }
        out.add(eventEntry(cls, fqcn, m, file, firstGenericArg(ret), true));
    }

    private static EntryPoint eventEntry(String cls, String fqcn, MethodDeclaration m, String file, String dest, boolean async) {
        // Fully-qualified id: listener methods are commonly overloaded (on(A), on(B)) AND a modular
        // monolith often has same-named listeners in different modules — neither the simple class name
        // nor the method alone is unique, so key on FQN#method(payload).
        String id = "event:" + fqcn + "#" + m.getNameAsString() + (dest != null ? "(" + dest + ")" : "@" + Ast.line(m));
        return new EntryPoint("event", id, null, null, null, dest, cls, m.getNameAsString(), file, Ast.line(m), async);
    }

    /** True for a source file under a {@code src/test/} root — never an application entry point. */
    private static boolean isTestSource(String relPath) {
        return relPath.contains("src/test/") || relPath.startsWith("test/") || relPath.contains("/test/");
    }

    private static String firstParamType(MethodDeclaration m) {
        return m.getParameters().isEmpty() ? null : simpleName(m.getParameter(0).getType().asString());
    }

    /** Simple name of a possibly-qualified/generic type: {@code a.b.Foo<X>} → {@code Foo}. */
    private static String simpleName(String type) {
        String t = type.contains("<") ? type.substring(0, type.indexOf('<')) : type;
        int dot = t.lastIndexOf('.');
        return (dot >= 0 ? t.substring(dot + 1) : t).replace("[]", "").trim();
    }

    /** First generic argument's simple name: {@code Consumer<a.b.Foo>} → {@code Foo}; null if none. */
    private static String firstGenericArg(String type) {
        int lt = type.indexOf('<');
        int gt = type.lastIndexOf('>');
        if (lt < 0 || gt <= lt) {
            return null;
        }
        String arg = type.substring(lt + 1, gt);
        int comma = arg.indexOf(',');
        if (comma >= 0) {
            arg = arg.substring(0, comma);
        }
        return simpleName(arg);
    }

    /** {@code Foo.class} → {@code Foo}; passes other strings through. */
    private static String stripClass(String s) {
        return s == null ? null : s.replace(".class", "").trim();
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
