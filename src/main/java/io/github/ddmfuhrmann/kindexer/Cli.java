package io.github.ddmfuhrmann.kindexer;

import io.github.ddmfuhrmann.kindexer.enrich.Deterministic;
import io.github.ddmfuhrmann.kindexer.enrich.Enricher;
import io.github.ddmfuhrmann.kindexer.enrich.EnrichmentProvider;
import io.github.ddmfuhrmann.kindexer.enrich.EnrichmentTask;
import io.github.ddmfuhrmann.kindexer.enrich.HttpAnthropicProvider;
import io.github.ddmfuhrmann.kindexer.manifest.Manifest;
import io.github.ddmfuhrmann.kindexer.manifest.Manifest.EnrichmentSection;
import io.github.ddmfuhrmann.kindexer.pipeline.Pipeline;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Entry point. Three commands:
 * <pre>
 *   run &lt;repo&gt; [--out DIR] [--no-llm | --provider sdk [--model M] [--max-tokens N] [--thinking on] [--effort LVL] [--task-model T=M]...]   one-shot (CI, determinism, sdk)
 *   extract &lt;repo&gt; [--out DIR]                                       agent phase 1: emit enrich requests
 *   assemble &lt;repo&gt; [--out DIR]                                      agent phase 2: fold in responses
 * </pre>
 * The deterministic layer always runs and always writes a manifest + HTML; enrichment is layered on
 * top and can be skipped or provided by either the agent (default skill flow) or the SDK.
 */
public final class Cli {

    // Haiku 4.5 is the default: the benchmark (docs/anthropic_benchmark.md) found it matches the
    // stronger models on structural quality at ~1/3 the cost and with zero ungrounded invention.
    // For the richer business voice on a deliverable, route behaviors to Sonnet (see the benchmark).
    private static final String DEFAULT_MODEL = "claude-haiku-4-5";

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || isHelp(args[0])) {
            printUsage();
            return;
        }
        String command = args[0];
        if (args.length < 2) {
            System.err.println("error: missing <repo> path");
            printUsage();
            System.exit(2);
            return;
        }
        Path repo = Path.of(args[1]).toAbsolutePath().normalize();
        if (!Files.isDirectory(repo)) {
            System.err.println("error: not a directory: " + repo);
            System.exit(2);
            return;
        }

        Options opt = Options.parse(args);
        Path outDir = opt.out != null ? Path.of(opt.out).toAbsolutePath()
                : repo.resolve(".knowledge-index/out");

        Pipeline pipeline = new Pipeline();
        Enricher enricher = new Enricher();

        System.err.println("[kindexer] parsing " + repo
                + (opt.exclude.isEmpty() ? "" : " (excluding: " + String.join(",", opt.exclude) + ")"));
        Deterministic det = pipeline.extract(repo, opt.exclude);

        Map<String, EnrichmentSection> enrichment = switch (command) {
            case "run" -> runEnrichment(det, repo, enricher, opt);
            case "extract" -> {
                var sections = enricher.emitRequests(det, repo, outDir);
                long pending = sections.values().stream().filter(s -> "pending".equals(s.model())).count();
                System.err.println("[kindexer] " + pending + " enrichment request(s) written to "
                        + outDir.resolve("enrich/requests"));
                System.err.println("[kindexer] fill enrich/responses/<task>.json, then run: assemble");
                yield sections;
            }
            case "assemble" -> enricher.assembleFromResponses(det, repo, outDir);
            default -> {
                System.err.println("error: unknown command '" + command + "'");
                printUsage();
                System.exit(2);
                yield Map.of();
            }
        };

        Manifest manifest = pipeline.manifest(repo, det, enrichment);
        pipeline.write(manifest, outDir);

        System.err.println("[kindexer] manifest: " + outDir.resolve("manifest.json"));
        System.err.println("[kindexer] html:     " + outDir.resolve("index.html"));
        System.out.println(outDir.resolve("manifest.json"));
    }

    private static Map<String, EnrichmentSection> runEnrichment(Deterministic det, Path repo, Enricher enricher, Options opt) {
        if (opt.noLlm) {
            System.err.println("[kindexer] --no-llm: deterministic layer only");
            return enricher.empty(det);
        }
        if ("sdk".equals(opt.provider)) {
            String modelLabel = opt.taskModels.isEmpty() ? "model " : "default model ";
            String thinkingDesc = opt.thinking ? "on" + (opt.effort != null ? "/" + opt.effort : "") : "off";
            System.err.println("[kindexer] enrichment via Anthropic API (" + modelLabel + opt.model
                    + ", max_tokens " + opt.maxTokens + ", thinking " + thinkingDesc + ")");
            if (!opt.taskModels.isEmpty()) {
                System.err.println("[kindexer] per-task model overrides: " + opt.taskModels
                        + " (tasks not listed use the default model)");
            }
            // Resolve a provider per task, caching one HttpAnthropicProvider per distinct model.
            Map<String, HttpAnthropicProvider> byModel = new HashMap<>();
            Function<EnrichmentTask, EnrichmentProvider> providerFor = task -> {
                String model = opt.taskModels.getOrDefault(task.name(), opt.model);
                return byModel.computeIfAbsent(model, m -> new HttpAnthropicProvider(m, opt.maxTokens, opt.thinking, opt.effort));
            };
            var result = enricher.runWithProvider(det, repo, providerFor);
            reportSpend(byModel);
            return result;
        }
        System.err.println("[kindexer] no enrichment provider (use --provider sdk, or the extract/assemble "
                + "agent flow); emitting deterministic layer only");
        return enricher.empty(det);
    }

    /** Sum the per-model usage into one run-total spend line (estimated, indicative prices). */
    private static void reportSpend(Map<String, HttpAnthropicProvider> byModel) {
        long in = 0;
        long out = 0;
        double cost = 0;
        for (HttpAnthropicProvider p : byModel.values()) {
            in += p.inputTokens();
            out += p.outputTokens();
            cost += p.estimatedCostUsd();
        }
        if (in == 0 && out == 0) {
            return; // nothing was actually called (all cache hits)
        }
        System.err.printf("[kindexer] estimated spend: %d in / %d out tok ≈ $%.4f (estimated; indicative prices)%n",
                in, out, cost);
    }

    private static boolean isHelp(String a) {
        return a.equals("-h") || a.equals("--help") || a.equals("help");
    }

    private static void printUsage() {
        System.out.println("""
                knowledge-index — deterministic Spring Boot knowledge extractor + LLM enrichment

                Usage:
                  run <repo> [--out DIR] [--no-llm | --provider sdk [--model M] [--max-tokens N] [--thinking on] [--effort LVL] [--task-model T=M]...]
                  extract <repo> [--out DIR]     emit enrichment requests (agent phase 1)
                  assemble <repo> [--out DIR]    fold in agent responses (agent phase 2)

                Options:
                  --out DIR        output dir (default: <repo>/.knowledge-index/out)
                  --no-llm         deterministic layer only (reproducible; for CI / determinism proof)
                  --provider sdk   enrich via Anthropic API (needs ANTHROPIC_API_KEY)
                  --model M        model id for --provider sdk (default: %s)
                  --max-tokens N   output token budget per enrichment call (default: %d)
                  --thinking on    enable model reasoning for --provider sdk (default: off);
                                   adaptive for modern models, extended (budget) for Haiku 4.5
                  --effort LVL     reasoning depth: low|medium|high|xhigh|max (implies --thinking on);
                                   output_config.effort on modern models, budget_tokens on Haiku 4.5
                  --task-model T=M route one enrichment task to a specific model (repeatable),
                                   e.g. --task-model behaviors=claude-opus-4-8
                  --exclude a,b    directory names to prune (nested/vendored projects), e.g. --exclude .skills

                Multi-module aware: every src/main/java and src/test/java in the tree is a source root.
                """.formatted(DEFAULT_MODEL, HttpAnthropicProvider.DEFAULT_MAX_TOKENS));
    }

    /** Minimal flag parser — avoids a CLI dependency. */
    private static final class Options {
        String out;
        boolean noLlm;
        String provider;
        String model = DEFAULT_MODEL;
        int maxTokens = HttpAnthropicProvider.DEFAULT_MAX_TOKENS;
        boolean thinking = false;
        String effort; // null = model default
        Map<String, String> taskModels = new java.util.LinkedHashMap<>();
        java.util.Set<String> exclude = new java.util.LinkedHashSet<>();

        static Options parse(String[] args) {
            Options o = new Options();
            for (int i = 2; i < args.length; i++) {
                switch (args[i]) {
                    case "--out" -> o.out = next(args, ++i);
                    case "--no-llm" -> o.noLlm = true;
                    case "--provider" -> o.provider = next(args, ++i);
                    case "--model" -> o.model = next(args, ++i);
                    case "--max-tokens" -> {
                        String v = next(args, ++i);
                        if (v != null) {
                            try {
                                o.maxTokens = Integer.parseInt(v.trim());
                            } catch (NumberFormatException e) {
                                System.err.println("[kindexer] invalid --max-tokens: " + v + " (using "
                                        + o.maxTokens + ")");
                            }
                        }
                    }
                    case "--thinking" -> {
                        String v = next(args, ++i);
                        o.thinking = v != null && (v.equalsIgnoreCase("on") || v.equalsIgnoreCase("true")
                                || v.equalsIgnoreCase("adaptive") || v.equalsIgnoreCase("enabled"));
                    }
                    case "--effort" -> {
                        String v = next(args, ++i);
                        java.util.Set<String> valid = java.util.Set.of("low", "medium", "high", "xhigh", "max");
                        if (v != null && valid.contains(v.toLowerCase())) {
                            o.effort = v.toLowerCase();
                            o.thinking = true; // effort implies reasoning on
                        } else {
                            System.err.println("[kindexer] invalid --effort: " + v + " (expected low|medium|high|xhigh|max)");
                        }
                    }
                    case "--task-model" -> {
                        String v = next(args, ++i);
                        int eq = v == null ? -1 : v.indexOf('=');
                        if (eq > 0) {
                            o.taskModels.put(v.substring(0, eq).trim(), v.substring(eq + 1).trim());
                        } else {
                            System.err.println("[kindexer] invalid --task-model (expected <task>=<model>): " + v);
                        }
                    }
                    case "--exclude" -> {
                        String v = next(args, ++i);
                        if (v != null) {
                            for (String d : v.split(",")) {
                                if (!d.isBlank()) o.exclude.add(d.trim());
                            }
                        }
                    }
                    default -> System.err.println("[kindexer] ignoring unknown option: " + args[i]);
                }
            }
            return o;
        }

        private static String next(String[] args, int i) {
            return i < args.length ? args[i] : null;
        }
    }
}
