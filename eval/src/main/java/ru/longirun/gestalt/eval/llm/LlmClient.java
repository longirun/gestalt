package ru.longirun.gestalt.eval.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * LLM-граница экстракции (OpenAI-совместимый роутер). Таймауты и повторы обязательны (ADR 24 §9):
 * молчаливая потеря батча запрещена — fail-fast с журналом.
 */
public final class LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final String reasoningEffort;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public LlmClient(String baseUrl, String apiKey, String model) {
        this(baseUrl, apiKey, model, "");
    }

    public LlmClient(String baseUrl, String apiKey, String model, String reasoningEffort) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.reasoningEffort = reasoningEffort;
    }

    public record Usage(int promptTokens, int completionTokens, int totalTokens) {
        public static final Usage ZERO = new Usage(0, 0, 0);
    }

    public record ChatResult(String content, Usage usage) {
    }

    public String chat(String systemPrompt, String userPayload) throws Exception {
        return chatWithUsage(systemPrompt, userPayload).content();
    }

    public ChatResult chatWithUsage(String systemPrompt, String userPayload) throws Exception {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("model", model);
        request.putArray("messages")
                .addObject().put("role", "system").put("content", systemPrompt);
        request.withArray("messages")
                .addObject().put("role", "user").put("content", userPayload);
        request.put("temperature", 0);
        if (!reasoningEffort.isBlank()) {
            request.put("reasoning_effort", reasoningEffort);
        }

        RuntimeException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpResponse<String> response = http.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(baseUrl + "/chat/completions"))
                                .timeout(Duration.ofSeconds(120))
                                .header("Authorization", "Bearer " + apiKey)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(request)))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 != 2) {
                    throw new IllegalStateException("LLM HTTP " + response.statusCode() + ": " + response.body());
                }
                JsonNode root = MAPPER.readTree(response.body());
                JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
                if (contentNode.isMissingNode()) {
                    throw new IllegalStateException("LLM response without content");
                }

                Usage usage = Usage.ZERO;
                JsonNode usageNode = root.path("usage");
                if (!usageNode.isMissingNode()) {
                    usage = new Usage(
                            usageNode.path("prompt_tokens").asInt(0),
                            usageNode.path("completion_tokens").asInt(0),
                            usageNode.path("total_tokens").asInt(0));
                }

                return new ChatResult(contentNode.asText(), usage);
            } catch (RuntimeException | InterruptedException | java.io.IOException e) {
                last = e instanceof RuntimeException re ? re : new IllegalStateException(e);
                if (attempt < 3) {
                    Thread.sleep(1000L * attempt);
                }
            }
        }
        throw last;
    }
}
