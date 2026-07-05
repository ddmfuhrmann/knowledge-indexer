package io.github.ddmfuhrmann.kindexer.extract;

import io.github.ddmfuhrmann.kindexer.ast.ProjectModel;
import io.github.ddmfuhrmann.kindexer.model.EntryPoints.EntryPoint;
import io.github.ddmfuhrmann.kindexer.model.EventFlows.EventFlow;
import io.github.ddmfuhrmann.kindexer.model.EventFlows.Party;
import io.github.ddmfuhrmann.kindexer.util.Ast;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Extractor 9 — the event choreography. Consumers are the event entry points; producers are publish
 * sites: {@code publishEvent(new X(...))}, an aggregate's {@code registerEvent(new X(...))}, or a
 * broker {@code send/publish/convertAndSend(..., new X(...))}. Matched by the event's simple type
 * name. Deterministic: everything sorted, producers/consumers de-duplicated.
 */
public final class EventFlowExtractor {

    private static final Set<String> PUBLISH_METHODS =
            Set.of("publishEvent", "registerEvent", "register", "publish", "send", "convertAndSend", "emit");

    /** Type-name suffixes that read as a domain event — used to keep publish scanning from false-firing. */
    private static final List<String> EVENT_SUFFIXES = List.of(
            "Event", "Created", "Updated", "Deleted", "Removed", "Added", "Changed", "Confirmed",
            "Cancelled", "Canceled", "Closed", "Opened", "Issued", "Returned", "Recorded", "Consumed",
            "Placed", "Shipped", "Delivered", "Paid", "Completed", "Started", "Registered");

    public List<EventFlow> extract(ProjectModel project, List<EntryPoint> entryPoints) {
        Map<String, List<Party>> consumers = new TreeMap<>();
        for (EntryPoint ep : entryPoints) {
            if ("event".equals(ep.category()) && ep.destination() != null) {
                consumers.computeIfAbsent(ep.destination(), k -> new ArrayList<>())
                        .add(new Party(ep.className(), ep.method(), ep.file(), ep.line(), ep.async()));
            }
        }

        Map<String, List<Party>> producers = new TreeMap<>();
        for (ProjectModel.ParsedFile pf : project.files()) {
            if (isTestSource(pf.relPath())) {
                continue;
            }
            for (MethodCallExpr call : pf.cu().findAll(MethodCallExpr.class)) {
                if (!PUBLISH_METHODS.contains(call.getNameAsString())) {
                    continue;
                }
                for (Expression arg : call.getArguments()) {
                    if (!(arg instanceof ObjectCreationExpr oce)) {
                        continue;
                    }
                    String event = simpleName(oce.getType().asString());
                    // Only accept it as an event if something consumes it, or it names like one — keeps
                    // ordinary object construction (new PageRequest(...), new Message(...)) out.
                    if (!consumers.containsKey(event) && !looksLikeEvent(event)) {
                        continue;
                    }
                    String cls = call.findAncestor(ClassOrInterfaceDeclaration.class)
                            .map(ClassOrInterfaceDeclaration::getNameAsString).orElse("?");
                    String method = call.findAncestor(MethodDeclaration.class)
                            .map(MethodDeclaration::getNameAsString).orElse("?");
                    producers.computeIfAbsent(event, k -> new ArrayList<>())
                            .add(new Party(cls, method, pf.relPath(), Ast.line(call), false));
                    break; // one event per publish call
                }
            }
        }

        Set<String> events = new TreeSet<>();
        events.addAll(producers.keySet());
        events.addAll(consumers.keySet());
        List<EventFlow> out = new ArrayList<>();
        for (String e : events) {
            out.add(new EventFlow(e, dedupe(producers.get(e)), dedupe(consumers.get(e))));
        }
        return out;
    }

    private static List<Party> dedupe(List<Party> parties) {
        if (parties == null) {
            return List.of();
        }
        Map<String, Party> byKey = new TreeMap<>();
        for (Party p : parties) {
            byKey.putIfAbsent(p.className() + "#" + p.method() + "@" + p.line(), p);
        }
        List<Party> list = new ArrayList<>(byKey.values());
        list.sort(Comparator.comparing(Party::className).thenComparing(Party::method).thenComparingInt(Party::line));
        return list;
    }

    private static boolean looksLikeEvent(String type) {
        for (String suffix : EVENT_SUFFIXES) {
            if (type.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static String simpleName(String type) {
        String t = type.contains("<") ? type.substring(0, type.indexOf('<')) : type;
        int dot = t.lastIndexOf('.');
        return (dot >= 0 ? t.substring(dot + 1) : t).trim();
    }

    private static boolean isTestSource(String relPath) {
        return relPath.contains("src/test/") || relPath.startsWith("test/") || relPath.contains("/test/");
    }
}
