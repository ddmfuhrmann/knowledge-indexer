package io.github.ddmfuhrmann.kindexer.pipeline;

import io.github.ddmfuhrmann.kindexer.ast.ProjectModel;
import io.github.ddmfuhrmann.kindexer.enrich.Deterministic;
import io.github.ddmfuhrmann.kindexer.extract.CallGraphExtractor;
import io.github.ddmfuhrmann.kindexer.extract.EntityExtractor;
import io.github.ddmfuhrmann.kindexer.extract.EntryPointExtractor;
import io.github.ddmfuhrmann.kindexer.extract.ErrorExtractor;
import io.github.ddmfuhrmann.kindexer.extract.EventFlowExtractor;
import io.github.ddmfuhrmann.kindexer.extract.GuardExtractor;
import io.github.ddmfuhrmann.kindexer.extract.InputConstraintExtractor;
import io.github.ddmfuhrmann.kindexer.extract.MigrationReader;
import io.github.ddmfuhrmann.kindexer.extract.StateMachineExtractor;
import io.github.ddmfuhrmann.kindexer.extract.TestScenarioExtractor;
import io.github.ddmfuhrmann.kindexer.git.GitInfo;
import io.github.ddmfuhrmann.kindexer.hash.ContentHash;
import io.github.ddmfuhrmann.kindexer.manifest.Manifest;
import io.github.ddmfuhrmann.kindexer.manifest.Manifest.Artifact;
import io.github.ddmfuhrmann.kindexer.manifest.Manifest.EnrichmentSection;
import io.github.ddmfuhrmann.kindexer.model.CallGraph;
import io.github.ddmfuhrmann.kindexer.render.HtmlRenderer;
import io.github.ddmfuhrmann.kindexer.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Wires the deterministic extractors, the manifest (single source of truth), and the HTML renderer.
 * The two layers are strictly ordered: extraction first (from the AST of the current commit), then
 * enrichment over that frozen material, then a manifest, then a derived HTML view.
 */
public final class Pipeline {

    /** Parse once, run all five extractors + the best-effort migration reader, in a fixed order. */
    public Deterministic extract(Path repoRoot) {
        return extract(repoRoot, java.util.Set.of());
    }

    /** {@code excludeDirs} prunes nested/vendored directory names from source discovery. */
    public Deterministic extract(Path repoRoot, java.util.Set<String> excludeDirs) {
        long t0 = System.nanoTime();
        ProjectModel project = ProjectModel.parse(repoRoot, excludeDirs);
        System.err.println("[kindexer] parsed working tree in " + ms(t0) + "ms; running extractors…");

        var entities = new EntityExtractor().extract(project);
        var entryPoints = new EntryPointExtractor().extract(project);
        // Call graph + state machines are the heavy pass; flag it so a long silence is explained.
        System.err.println("[kindexer] building call graph from " + entryPoints.size() + " entry point(s)…");
        CallGraph callGraph = new CallGraphExtractor().extract(project, entryPoints);
        var stateMachines = new StateMachineExtractor().extract(project, callGraph);
        var tests = new TestScenarioExtractor().extract(project);
        var migrations = new MigrationReader().read(repoRoot);
        ErrorExtractor errors = new ErrorExtractor();
        var throwSites = errors.throwSites(project);
        var exceptionStatuses = errors.exceptionStatuses(project);
        var inputConstraints = new InputConstraintExtractor().extract(project, entryPoints);
        var guardChecks = new GuardExtractor().extract(project);
        var eventFlows = new EventFlowExtractor().extract(project, entryPoints);

        System.err.printf(
                "[kindexer] deterministic layer: %d entities, %d entry points, %d nodes, %d edges, "
                        + "%d tests, %d event flows (%dms total)%n",
                entities.size(), entryPoints.size(), callGraph.nodes().size(), callGraph.edges().size(),
                tests.size(), eventFlows.size(), ms(t0));

        return new Deterministic(entities, entryPoints, callGraph, stateMachines, tests, migrations,
                throwSites, exceptionStatuses, inputConstraints, guardChecks, eventFlows);
    }

    private static long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** Each artifact carries the content hash of its own data — the enrichment cache key upstream. */
    public Map<String, Artifact> artifacts(Deterministic det) {
        Map<String, Artifact> map = new TreeMap<>();
        map.put("entities", artifact(det.entities()));
        map.put("entryPoints", artifact(det.entryPoints()));
        map.put("callGraph", artifact(det.callGraph()));
        map.put("stateMachines", artifact(det.stateMachines()));
        map.put("testScenarios", artifact(det.testScenarios()));
        map.put("migrations", artifact(det.migrations()));
        map.put("throwSites", artifact(det.throwSites()));
        map.put("exceptionStatuses", artifact(det.exceptionStatuses()));
        map.put("inputConstraints", artifact(det.inputConstraints()));
        map.put("guardChecks", artifact(det.guardChecks()));
        map.put("eventFlows", artifact(det.eventFlows()));
        return map;
    }

    private static Artifact artifact(Object data) {
        return new Artifact(ContentHash.of(data), data);
    }

    public Manifest manifest(Path repoRoot, Deterministic det, Map<String, EnrichmentSection> enrichment) {
        GitInfo git = GitInfo.read(repoRoot);
        String name = repoRoot.toAbsolutePath().getFileName().toString();
        Manifest.ProjectInfo project = new Manifest.ProjectInfo(name, git.generatedAt(), git.commit(), git.commitMessage());
        return new Manifest(project, artifacts(det), enrichment);
    }

    /** Write manifest.json (canonical, sorted) and the derived index.html into {@code outDir}. */
    public void write(Manifest manifest, Path outDir) throws IOException {
        Files.createDirectories(outDir);
        Path manifestFile = outDir.resolve("manifest.json");
        Files.writeString(manifestFile, Json.pretty().writeValueAsString(manifest) + "\n");
        Path html = outDir.resolve("index.html");
        Files.writeString(html, new HtmlRenderer().render(manifest));
    }
}
