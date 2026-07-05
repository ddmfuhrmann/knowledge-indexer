package io.github.ddmfuhrmann.kindexer.enrich;

import io.github.ddmfuhrmann.kindexer.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Headless enrichment provider: a thin, dependency-free client for the Anthropic Messages API via
 * the JDK HTTP client. Key from {@code ANTHROPIC_API_KEY}; base URL from {@code ANTHROPIC_BASE_URL}
 * (defaults to the public endpoint); model injected by the caller. Used only on {@code --provider
 * sdk}; the default agent path never constructs this.
 *
 * <p>Hardening for real (non-mock) runs: {@code thinking} is explicitly disabled so the whole
 * {@code max_tokens} budget goes to the JSON array (otherwise recent models run adaptive thinking by
 * default and burn the budget before emitting JSON, silently truncating it); transient failures
 * (429/5xx/529, I/O) are retried with exponential backoff honouring {@code retry-after}; and a
 * {@code max_tokens} stop is surfaced as a warning so truncation is never masked.
 */
public final class HttpAnthropicProvider implements EnrichmentProvider {

    /** Default output budget. With thinking disabled the whole budget is available for the JSON. */
    public static final int DEFAULT_MAX_TOKENS = 16000;

    private static final int MAX_ATTEMPTS = 4;
    private static final long BASE_BACKOFF_MS = 1000;
    private static final long MAX_BACKOFF_MS = 30_000;

    private final String model;
    private final int maxTokens;
    private final boolean thinking;
    private final String effort; // low|medium|high|xhigh|max, or null for the model default
    private final String apiKey;
    private final String baseUrl;
    private final HttpClient http;

    // Accumulated usage across all calls made through this provider (for the run-total spend line).
    private long inputTokens;
    private long outputTokens;
    private long cacheReadTokens;
    private long cacheWriteTokens;
    private double estimatedCostUsd;
    private boolean priceKnown = true;

    public HttpAnthropicProvider(String model) {
        this(model, DEFAULT_MAX_TOKENS, false, null);
    }

    public HttpAnthropicProvider(String model, int maxTokens) {
        this(model, maxTokens, false, null);
    }

    public HttpAnthropicProvider(String model, int maxTokens, boolean thinking) {
        this(model, maxTokens, thinking, null);
    }

    public HttpAnthropicProvider(String model, int maxTokens, boolean thinking, String effort) {
        this.model = model;
        this.maxTokens = maxTokens > 0 ? maxTokens : DEFAULT_MAX_TOKENS;
        this.thinking = thinking;
        this.effort = (effort == null || effort.isBlank()) ? null : effort.toLowerCase();
        this.apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "ANTHROPIC_API_KEY is not set — required for --provider sdk (use the default agent provider otherwise).");
        }
        String base = System.getenv("ANTHROPIC_BASE_URL");
        this.baseUrl = (base == null || base.isBlank()) ? "https://api.anthropic.com" : base.replaceAll("/+$", "");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    }

    @Override
    public String modelId() {
        return "sdk:" + model;
    }

    /**
     * Thinking is off by default (whole {@code max_tokens} budget goes to the JSON, tight + cheap).
     * When enabled, the config is model-aware: modern models (Sonnet 4.6+/5, Opus 4.6+, Fable) take
     * adaptive thinking; older ones (Haiku 4.5, Sonnet 4.5, 3.x) take extended thinking with a
     * {@code budget_tokens} strictly below {@code max_tokens}. This lets the interpretive behaviors
     * task reason about business framing instead of restating the endpoint.
     */
    private void applyThinking(ObjectNode body) {
        boolean fable = isFable(model); // Fable/Mythos: thinking is always on; {type:"disabled"} 400s.
        ObjectNode t = body.putObject("thinking");
        if (!thinking && !fable) {
            t.put("type", "disabled");
            return;
        }
        if (usesExtendedThinking(model)) {
            // Older models: no adaptive/effort — the reasoning "level" is budget_tokens.
            t.put("type", "enabled").put("budget_tokens", budgetForEffort());
            return;
        }
        // Modern models (and Fable, always-on): adaptive thinking; effort tunes the depth.
        t.put("type", "adaptive");
        if (effort != null) {
            body.putObject("output_config").put("effort", effort);
        }
    }

    /** Map the effort level to a {@code budget_tokens} for extended-thinking models; clamp < max_tokens. */
    private int budgetForEffort() {
        int base = switch (effort == null ? "high" : effort) {
            case "low" -> 2000;
            case "medium" -> 4000;
            case "xhigh" -> 12000;
            case "max" -> maxTokens - 1024;
            default -> 8000; // high / unknown
        };
        return Math.max(1024, Math.min(base, maxTokens - 1024));
    }

    /** Older models that need {@code {type:"enabled", budget_tokens}} rather than adaptive thinking. */
    private static boolean usesExtendedThinking(String model) {
        String m = model.toLowerCase();
        return m.contains("haiku") || m.contains("sonnet-4-5") || m.contains("sonnet-3")
                || m.contains("opus-4-5") || m.contains("opus-3") || m.contains("claude-2");
    }

    private static boolean isFable(String model) {
        String m = model.toLowerCase();
        return m.contains("fable") || m.contains("mythos");
    }

    @Override
    public String complete(String prompt) throws Exception {
        ObjectNode body = Json.mapper().createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        applyThinking(body);
        var messages = body.putArray("messages");
        ObjectNode msg = messages.addObject();
        msg.put("role", "user");
        msg.put("content", prompt);

        String payload = Json.mapper().writeValueAsString(body);
        HttpResponse<String> response = sendWithRetry(payload);

        JsonNode root = Json.mapper().readTree(response.body());
        if ("max_tokens".equals(root.path("stop_reason").asText())) {
            System.err.println("[kindexer] " + model
                    + " truncated the response (stop_reason=max_tokens) — raise --max-tokens (currently "
                    + maxTokens + ")");
        }
        recordUsage(root.path("usage"));
        JsonNode content = root.get("content");
        StringBuilder sb = new StringBuilder();
        if (content != null && content.isArray()) {
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText())) {
                    sb.append(block.path("text").asText());
                }
            }
        }
        return sb.toString();
    }

    public long inputTokens() {
        return inputTokens;
    }

    public long outputTokens() {
        return outputTokens;
    }

    /** Estimated USD spent through this provider (indicative — list prices, may be stale). */
    public double estimatedCostUsd() {
        return estimatedCostUsd;
    }

    /** Read the response {@code usage}, log this call's tokens + estimated cost, and accumulate. */
    private void recordUsage(JsonNode usage) {
        long in = usage.path("input_tokens").asLong(0);
        long out = usage.path("output_tokens").asLong(0);
        long cr = usage.path("cache_read_input_tokens").asLong(0);
        long cw = usage.path("cache_creation_input_tokens").asLong(0);
        inputTokens += in;
        outputTokens += out;
        cacheReadTokens += cr;
        cacheWriteTokens += cw;

        double[] price = pricePerMTok(model); // {input, output} USD per 1M tokens
        String costStr;
        if (price == null) {
            priceKnown = false;
            costStr = "cost n/a for " + model;
        } else {
            // cache reads bill ~0.1x input; cache writes ~1.25x input.
            double cost = (in * price[0] + out * price[1] + cr * price[0] * 0.1 + cw * price[0] * 1.25) / 1_000_000.0;
            estimatedCostUsd += cost;
            costStr = String.format("≈ $%.4f (estimated)", cost);
        }
        String cache = (cr > 0 || cw > 0) ? ", cache_read " + cr + ", cache_write " + cw : "";
        System.err.println("[kindexer] " + modelId() + " usage: in " + in + ", out " + out + cache + " " + costStr);
    }

    /** Indicative list prices (USD per 1M tokens) as {input, output}; null when the model is unknown. */
    private static double[] pricePerMTok(String model) {
        String m = model.toLowerCase();
        if (m.contains("haiku")) return new double[]{1.0, 5.0};
        if (m.contains("opus")) return new double[]{5.0, 25.0};
        if (m.contains("fable") || m.contains("mythos")) return new double[]{10.0, 50.0};
        if (m.contains("sonnet")) return new double[]{3.0, 15.0};
        return null;
    }

    /**
     * Send the request, retrying transient failures (429, 5xx incl. 529, and I/O/timeout) with
     * exponential backoff. Honours a {@code retry-after} header when present. Non-retryable statuses
     * (e.g. 400/401/404) and an exhausted retry budget throw.
     */
    private HttpResponse<String> sendWithRetry(String payload) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/messages"))
                        .header("x-api-key", apiKey)
                        .header("anthropic-version", "2023-06-01")
                        .header("content-type", "application/json")
                        .timeout(Duration.ofSeconds(120))
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build();

                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status / 100 == 2) {
                    return response;
                }
                if (!isRetryable(status) || attempt == MAX_ATTEMPTS) {
                    throw new IllegalStateException("Anthropic API " + status + ": " + response.body());
                }
                long wait = retryAfterMs(response).orElse(backoffMs(attempt));
                System.err.println("[kindexer] Anthropic API " + status + " — attempt " + attempt
                        + "/" + MAX_ATTEMPTS + ", waiting " + wait + "ms");
                sleep(wait);
            } catch (java.io.IOException e) {
                // Covers connection failures and java.net.http.HttpTimeoutException (a subtype).
                last = e;
                if (attempt == MAX_ATTEMPTS) {
                    break;
                }
                long wait = backoffMs(attempt);
                System.err.println("[kindexer] Anthropic API I/O " + e.getClass().getSimpleName()
                        + " — attempt " + attempt + "/" + MAX_ATTEMPTS + ", waiting " + wait + "ms");
                sleep(wait);
            }
        }
        throw last != null ? last : new IllegalStateException("Anthropic API: retries exhausted");
    }

    private static boolean isRetryable(int status) {
        return status == 429 || status == 529 || (status >= 500 && status < 600);
    }

    private static long backoffMs(int attempt) {
        long exp = BASE_BACKOFF_MS * (1L << (attempt - 1));
        return Math.min(exp, MAX_BACKOFF_MS);
    }

    private static java.util.Optional<Long> retryAfterMs(HttpResponse<String> response) {
        return response.headers().firstValue("retry-after").flatMap(v -> {
            try {
                return java.util.Optional.of(Math.min(Long.parseLong(v.trim()) * 1000, MAX_BACKOFF_MS));
            } catch (NumberFormatException e) {
                return java.util.Optional.empty();
            }
        });
    }

    private static void sleep(long ms) throws InterruptedException {
        if (ms > 0) {
            Thread.sleep(ms);
        }
    }
}
