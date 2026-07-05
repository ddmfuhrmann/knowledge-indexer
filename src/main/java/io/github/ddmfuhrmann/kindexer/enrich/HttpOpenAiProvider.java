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
 * Headless enrichment provider for any endpoint speaking the OpenAI
 * {@code /v1/chat/completions} shape. One client covers hosted OpenAI <em>and</em> local runners
 * (Ollama, LM Studio, llama.cpp, vLLM) and OpenAI-compatible aggregators (Groq, OpenRouter, …) —
 * only the {@code --base-url} + {@code --model} change. Used on {@code --provider openai}.
 *
 * <p>The base URL is expected to already include {@code /v1} (e.g.
 * {@code http://localhost:11434/v1}); the request POSTs to {@code <baseUrl>/chat/completions}. The
 * key comes from {@code OPENAI_API_KEY} and is <em>optional</em>: a local model (Ollama) needs none,
 * so when it is absent no {@code Authorization} header is sent. Transient failures (429/5xx, I/O) are
 * retried with exponential backoff honouring {@code retry-after}, mirroring the Anthropic path.
 *
 * <p>Small/local models return messier JSON and weaker anchoring — {@code JsonExtract} tolerates
 * fenced/prose-wrapped output and the evidence gate drops unanchored items, so a weak model shows up
 * as a low keep-rate rather than a hard failure.
 */
public final class HttpOpenAiProvider implements EnrichmentProvider {

    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    private static final int MAX_ATTEMPTS = 4;
    private static final long BASE_BACKOFF_MS = 1000;
    private static final long MAX_BACKOFF_MS = 30_000;
    // Local models (Ollama/CPU) are far slower than a hosted API, so allow more per-request wall
    // time than the Anthropic path's 120s. Overridable via KINDEXER_OPENAI_TIMEOUT_S for a very slow
    // box or a big prompt. A timeout is not retried (see sendWithRetry) — retrying slowness wastes it.
    private static final int DEFAULT_TIMEOUT_S = 300;

    private final String model;
    private final int maxTokens;
    private final String apiKey; // nullable — a local model needs no key
    private final String baseUrl;
    private final String reasoningEffort; // nullable — OpenAI reasoning_effort (Ollama: "none" disables thinking)
    private final Duration requestTimeout;
    private final HttpClient http;

    // Accumulated usage across all calls made through this provider (for the run-total spend line).
    private long inputTokens;
    private long outputTokens;

    public HttpOpenAiProvider(String model, int maxTokens, String baseUrl) {
        this(model, maxTokens, baseUrl, null);
    }

    public HttpOpenAiProvider(String model, int maxTokens, String baseUrl, String reasoningEffort) {
        this.model = model;
        this.maxTokens = maxTokens > 0 ? maxTokens : HttpAnthropicProvider.DEFAULT_MAX_TOKENS;
        String key = System.getenv("OPENAI_API_KEY");
        this.apiKey = (key == null || key.isBlank()) ? null : key;
        String base = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl;
        this.baseUrl = base.replaceAll("/+$", "");
        this.reasoningEffort = (reasoningEffort == null || reasoningEffort.isBlank()) ? null : reasoningEffort.toLowerCase();
        this.requestTimeout = Duration.ofSeconds(timeoutSeconds());
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    }

    @Override
    public String modelId() {
        return "openai:" + model;
    }

    /** Per-request wall-clock budget in seconds; {@code KINDEXER_OPENAI_TIMEOUT_S} overrides the default. */
    private static int timeoutSeconds() {
        String v = System.getenv("KINDEXER_OPENAI_TIMEOUT_S");
        if (v != null && !v.isBlank()) {
            try {
                int n = Integer.parseInt(v.trim());
                if (n > 0) {
                    return n;
                }
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
        }
        return DEFAULT_TIMEOUT_S;
    }

    @Override
    public String complete(String prompt) throws Exception {
        ObjectNode body = Json.mapper().createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("temperature", 0); // maximise consistency / keep-rate; widely supported (Ollama et al.)
        if (reasoningEffort != null) {
            // OpenAI `reasoning_effort`; on Ollama's endpoint "none" disables a thinking model's
            // chain-of-thought so the whole output budget goes to the JSON (thinking otherwise burns
            // it before/mid-array and truncates — the same failure the Anthropic path disables).
            body.put("reasoning_effort", reasoningEffort);
        }
        var messages = body.putArray("messages");
        ObjectNode msg = messages.addObject();
        msg.put("role", "user");
        msg.put("content", prompt);

        String payload = Json.mapper().writeValueAsString(body);
        HttpResponse<String> response = sendWithRetry(payload);

        JsonNode root = Json.mapper().readTree(response.body());
        if (root.hasNonNull("error")) {
            System.err.println("[kindexer] " + modelId() + " returned an error: " + root.get("error").toString());
            return "";
        }
        JsonNode choice = root.path("choices").path(0);
        if ("length".equals(choice.path("finish_reason").asText())) {
            System.err.println("[kindexer] " + model
                    + " truncated the response (finish_reason=length) — raise --max-tokens (currently "
                    + maxTokens + ")");
        }
        recordUsage(root.path("usage"));
        return choice.path("message").path("content").asText("");
    }

    @Override
    public long inputTokens() {
        return inputTokens;
    }

    @Override
    public long outputTokens() {
        return outputTokens;
    }

    /** Read the OpenAI-shaped {@code usage} and log this call's tokens. Cost is unknown/free here. */
    private void recordUsage(JsonNode usage) {
        long in = usage.path("prompt_tokens").asLong(0);
        long out = usage.path("completion_tokens").asLong(0);
        inputTokens += in;
        outputTokens += out;
        System.err.println("[kindexer] " + modelId() + " usage: in " + in + ", out " + out + " cost n/a");
    }

    /**
     * Send the request, retrying transient failures (429, 5xx, and I/O/timeout) with exponential
     * backoff. Honours a {@code retry-after} header when present. Non-retryable statuses (e.g.
     * 400/401/404) and an exhausted retry budget throw.
     */
    private HttpResponse<String> sendWithRetry(String payload) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                        .header("content-type", "application/json")
                        .timeout(requestTimeout)
                        .POST(HttpRequest.BodyPublishers.ofString(payload));
                if (apiKey != null) {
                    req.header("Authorization", "Bearer " + apiKey);
                }

                HttpResponse<String> response = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status / 100 == 2) {
                    return response;
                }
                if (!isRetryable(status) || attempt == MAX_ATTEMPTS) {
                    throw new IllegalStateException("OpenAI API " + status + ": " + response.body());
                }
                long wait = retryAfterMs(response).orElse(backoffMs(attempt));
                System.err.println("[kindexer] OpenAI API " + status + " — attempt " + attempt
                        + "/" + MAX_ATTEMPTS + ", waiting " + wait + "ms");
                sleep(wait);
            } catch (java.net.http.HttpTimeoutException e) {
                // A slow local model won't get faster on retry — fail fast with an actionable hint
                // instead of burning MAX_ATTEMPTS × the timeout.
                throw new IllegalStateException("OpenAI API request timed out after " + requestTimeout.toSeconds()
                        + "s — the model is too slow for this prompt. Try a smaller/faster model, shrink the"
                        + " behaviors prompt with --behaviors-chunk N, or raise KINDEXER_OPENAI_TIMEOUT_S.", e);
            } catch (java.io.IOException e) {
                // Connection failures (server down, reset) — worth a retry.
                last = e;
                if (attempt == MAX_ATTEMPTS) {
                    break;
                }
                long wait = backoffMs(attempt);
                System.err.println("[kindexer] OpenAI API I/O " + e.getClass().getSimpleName()
                        + " — attempt " + attempt + "/" + MAX_ATTEMPTS + ", waiting " + wait + "ms");
                sleep(wait);
            }
        }
        throw last != null ? last : new IllegalStateException("OpenAI API: retries exhausted");
    }

    private static boolean isRetryable(int status) {
        return status == 429 || (status >= 500 && status < 600);
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
