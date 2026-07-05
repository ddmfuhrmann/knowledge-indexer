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
 */
public final class HttpAnthropicProvider implements EnrichmentProvider {

    private final String model;
    private final String apiKey;
    private final String baseUrl;
    private final HttpClient http;

    public HttpAnthropicProvider(String model) {
        this.model = model;
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

    @Override
    public String complete(String prompt) throws Exception {
        ObjectNode body = Json.mapper().createObjectNode();
        body.put("model", model);
        body.put("max_tokens", 4096);
        var messages = body.putArray("messages");
        ObjectNode msg = messages.addObject();
        msg.put("role", "user");
        msg.put("content", prompt);

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/messages"))
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(Json.mapper().writeValueAsString(body)))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Anthropic API " + response.statusCode() + ": " + response.body());
        }
        JsonNode root = Json.mapper().readTree(response.body());
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
}
