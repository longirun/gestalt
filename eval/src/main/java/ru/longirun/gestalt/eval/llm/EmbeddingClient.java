package ru.longirun.gestalt.eval.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Эмбеддинг-граница read-time селекции дайджеста (концепт 16: векторный поиск по
 * statement). OpenAI-совместимый /embeddings; провайдер — routerai/bge-m3, тот же,
 * что в honcho (1024d — совпадает с facts.embedding vector(1024)). Таймауты и
 * повторы обязательны (ADR 24 §9): молчаливая потеря батча запрещена — fail-fast.
 */
public final class EmbeddingClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public EmbeddingClient(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    /** Имя модели — для метаданных и digest-строки answer_fp. */
    public String model() {
        return model;
    }

    /** Один текст → вектор. */
    public double[] embedOne(String text) throws Exception {
        return embed(List.of(text)).getFirst();
    }

    /**
     * Батч текстов → векторы в порядке входа. Ответ валидируется: полнота
     * (вектор на каждый input), корректность index, единая размерность.
     */
    public List<double[]> embed(List<String> texts) throws Exception {
        if (texts.isEmpty()) {
            return List.of();
        }
        ObjectNode request = MAPPER.createObjectNode();
        request.put("model", model);
        ArrayNode input = request.putArray("input");
        texts.forEach(input::add);
        // роутеры вне api.openai.com отдают float; base64 тянет только прокачанный хост
        request.put("encoding_format", "float");

        RuntimeException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpResponse<String> response = http.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(baseUrl + "/embeddings"))
                                .timeout(Duration.ofSeconds(60))
                                .header("Authorization", "Bearer " + apiKey)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(request)))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 != 2) {
                    throw new IllegalStateException("Embedding HTTP " + response.statusCode() + ": " + response.body());
                }
                return parse(MAPPER.readTree(response.body()), texts.size());
            } catch (RuntimeException | InterruptedException | java.io.IOException e) {
                last = e instanceof RuntimeException re ? re : new IllegalStateException(e);
                if (attempt < 3) {
                    Thread.sleep(1000L * attempt);
                }
            }
        }
        throw last;
    }

    private static List<double[]> parse(JsonNode root, int expected) {
        JsonNode data = root.path("data");
        if (!data.isArray() || data.size() != expected) {
            throw new IllegalStateException("Embedding response: expected " + expected
                    + " vectors, got " + (data.isArray() ? data.size() : "none"));
        }
        double[][] vectors = new double[expected][];
        for (JsonNode item : data) {
            int index = item.path("index").asInt(-1);
            if (index < 0 || index >= expected || vectors[index] != null) {
                throw new IllegalStateException("Embedding response: bad/duplicate index " + index);
            }
            JsonNode embedding = item.path("embedding");
            if (!embedding.isArray() || embedding.isEmpty()) {
                throw new IllegalStateException("Embedding response: empty vector at index " + index);
            }
            double[] vector = new double[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vector[i] = embedding.get(i).asDouble();
            }
            vectors[index] = vector;
        }
        for (int i = 1; i < vectors.length; i++) {
            if (vectors[i].length != vectors[0].length) {
                throw new IllegalStateException("Embedding response: mixed dimensions ("
                        + vectors[0].length + " vs " + vectors[i].length + ")");
            }
        }
        return List.of(vectors);
    }
}
