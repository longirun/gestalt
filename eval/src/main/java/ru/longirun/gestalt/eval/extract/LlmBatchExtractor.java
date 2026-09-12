package ru.longirun.gestalt.eval.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.longirun.gestalt.eval.ingest.RawMessage;
import ru.longirun.gestalt.eval.llm.LlmClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Encoding, fast path (Р6/Р8): один LLM-вызов на батч.
 * Сериализация батча в user-payload, разбор JSON-ответа, валидация схемы.
 */
public final class LlmBatchExtractor implements FactExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> DOMAINS = Set.of("WORLD", "PSYCHE");
    private static final Set<String> KINDS = Set.of("STATE", "NARRATIVE", "EVENT");
    private static final Set<String> SCOPES = Set.of("SESSION", "PROJECT", "USER");
    private final LlmClient llm;

    public LlmBatchExtractor(LlmClient llm) {
        this.llm = llm;
    }

    public record ExtractionBatchResult(
            List<ExtractedFact> facts,
            LlmClient.Usage usage,
            long durationMillis) {
    }

    public ExtractionBatchResult extractWithMetrics(List<RawMessage> batch) {
        if (batch == null || batch.isEmpty()) {
            return new ExtractionBatchResult(List.of(), LlmClient.Usage.ZERO, 0);
        }

        StringBuilder payload = new StringBuilder("Batch of chronological messages to analyze:\n\n");
        for (RawMessage msg : batch) {
            payload.append("[").append(msg.id()).append("] ")
                    .append(msg.peerName()).append(": ")
                    .append(msg.content()).append("\n");
        }

        long start = System.currentTimeMillis();
        try {
            LlmClient.ChatResult res;
            List<ExtractedFact> facts;
            try {
                res = llm.chatWithUsage(ExtractionPrompt.SYSTEM, payload.toString());
                facts = parseResponse(res.content());
            } catch (IllegalArgumentException | LlmClient.TruncatedResponseException e) {
                // greedy-петля локального экстрактора (t=0): один факт тиражируется,
                // пока ответ не оборвётся посередине JSON; лечится температурным повтором
                System.err.println("[EXTRACTION RETRY] broken JSON, retrying batch with temperature 0.3: " + e.getMessage());
                try {
                    res = llm.chatWithUsage(ExtractionPrompt.SYSTEM, payload.toString(), 0.3);
                    facts = parseResponse(res.content());
                } catch (IllegalArgumentException | LlmClient.TruncatedResponseException e2) {
                    System.err.println("[EXTRACTION RETRY] still broken, last try with temperature 0.7: " + e2.getMessage());
                    res = llm.chatWithUsage(ExtractionPrompt.SYSTEM, payload.toString(), 0.7);
                    facts = parseResponse(res.content());
                }
            }
            long duration = System.currentTimeMillis() - start;
            return new ExtractionBatchResult(facts, res.usage(), duration);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to extract facts for batch: " + e.getMessage(), e);
        }
    }

    @Override
    public List<ExtractedFact> extract(List<RawMessage> batch) {
        return extractWithMetrics(batch).facts();
    }

    public static List<ExtractedFact> parseResponse(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return List.of();
        }

        String cleaned = cleanCodeFences(rawResponse.trim());
        List<ExtractedFact> facts = new ArrayList<>();

        try {
            JsonNode root = MAPPER.readTree(cleaned);
            if (!root.isArray()) {
                if (root.has("facts") && root.get("facts").isArray()) {
                    root = root.get("facts");
                } else {
                    return List.of();
                }
            }

            for (JsonNode item : root) {
                String rawKind = item.path("kind").asText("STATE").toUpperCase().trim();
                String rawDomain = item.path("domain").asText("WORLD").toUpperCase().trim();
                String rawScope = item.path("scope").asText("PROJECT").toUpperCase().trim();

                String subject = item.path("subject").asText("").trim();
                String predicate = item.path("predicate").asText("").trim();
                String object = item.path("object").asText("").trim();
                String statement = item.path("statement").asText("").trim();

                // Нормализация domain ∈ {WORLD, PSYCHE}
                String domain = DOMAINS.contains(rawDomain) ? rawDomain : "WORLD";
                if ("PSYCHE".equals(rawKind)) {
                    domain = "PSYCHE";
                }

                // Нормализация kind ∈ {STATE, NARRATIVE, EVENT}
                String kind;
                if (KINDS.contains(rawKind)) {
                    kind = rawKind;
                } else if ("EVENT".equalsIgnoreCase(rawKind)) {
                    kind = "EVENT";
                } else if (!object.isBlank() && !predicate.isBlank()) {
                    kind = "STATE";
                } else {
                    kind = "NARRATIVE";
                }

                // Нормализация scope ∈ {SESSION, PROJECT, USER}
                String scope = SCOPES.contains(rawScope) ? rawScope : "PROJECT";

                Map<String, String> conditions = new HashMap<>();
                JsonNode condNode = item.path("conditions");
                if (condNode.isObject()) {
                    condNode.fields().forEachRemaining(entry ->
                            conditions.put(entry.getKey(), entry.getValue().asText()));
                }

                List<Long> evidence = new ArrayList<>();
                JsonNode evNode = item.has("evidence_ids") ? item.get("evidence_ids") : item.get("evidence");
                if (evNode != null && evNode.isArray()) {
                    for (JsonNode id : evNode) {
                        if (id.isNumber()) {
                            evidence.add(id.asLong());
                        } else if (id.isTextual()) {
                            // локальные экстракторы шлют evidence_ids строками ("3064"), не числами
                            try {
                                evidence.add(Long.parseLong(id.asText().trim()));
                            } catch (NumberFormatException ignored) {
                                // не-числовая ссылка — пропускаем, остальной evidence сохраняется
                            }
                        }
                    }
                }

                if (!subject.isBlank() || !statement.isBlank()) {
                    facts.add(new ExtractedFact(
                            kind,
                            subject,
                            predicate,
                            object,
                            statement,
                            domain,
                            scope,
                            conditions,
                            evidence));
                }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse LLM extraction JSON: " + cleaned, e);
        }

        return facts;
    }

    private static String cleanCodeFences(String response) {
        String s = response.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline != -1) {
                s = s.substring(firstNewline + 1);
            }
            if (s.endsWith("```")) {
                s = s.substring(0, s.length() - 3);
            }
        }
        return s.trim();
    }
}
