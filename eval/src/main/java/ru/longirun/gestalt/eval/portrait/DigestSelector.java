package ru.longirun.gestalt.eval.portrait;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Read-time селекция дайджеста (E7, research/26 §5): вместо полного слепка плечу A
 * инъецируется глобальный top-K фактов по косинусу embedding(statement) ↔ embedding(trigger).
 * Порога косинуса нет — отбор только рангом; вывод сохраняет секционную структуру слепка.
 * Чистая логика без IO: векторы и метаданы приходят параметрами, HTTP/PG-границы —
 * у вызывающих (конвенция тестирования HTTP-адаптеров).
 */
public final class DigestSelector {

    /** Размерность facts.embedding — прибита схемой sql/001 (vector(1024), bge-m3). */
    public static final int EMBEDDING_DIMS = 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> SECTIONS = List.of("critical", "constructs", "preferences");

    private DigestSelector() {
    }

    /** Факт слепка: секция, строка дайджеста и метаданные ранжирования. */
    private record FactRef(UUID id, String section, String line, int reinforcement, int origin) {
    }

    /**
     * Дайджест по слепку: triggerVector == null → полный слепок (режим без селекции,
     * E6), иначе top-K фактов по косинусу. Ранг: similarity DESC → reinforcement DESC →
     * createdAt ASC → позиция в слепке (детерминизм). Факт без вектора при селекции —
     * громкий отказ: селекция не имеет права молча выкидывать факты из отбора.
     */
    public static String render(String snapshotJson, double[] triggerVector,
                                Map<UUID, double[]> factVectors,
                                Map<UUID, OffsetDateTime> createdAtById, int topK) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return "";
        }
        List<FactRef> refs = parseRefs(snapshotJson);
        if (refs.isEmpty()) {
            return "";
        }
        List<FactRef> ordered = refs;
        if (triggerVector != null) {
            if (topK < 1) {
                throw new IllegalArgumentException("digest.top-k must be >= 1, got " + topK);
            }
            List<FactRef> scored = new ArrayList<>(refs);
            Comparator<FactRef> bySimilarity = Comparator
                    .comparingDouble((FactRef r) -> cosine(triggerVector, requireVector(r, factVectors)))
                    .reversed();
            scored.sort(bySimilarity
                    .thenComparing(Comparator.comparingInt(FactRef::reinforcement).reversed())
                    .thenComparing(factRef -> createdAtById.getOrDefault(factRef.id(), OffsetDateTime.MIN))
                    .thenComparingInt(FactRef::origin));
            ordered = scored.subList(0, Math.min(topK, scored.size()));
        }
        return renderSections(refs, ordered);
    }

    /** id фактов слепка — для загрузки векторов/дат из facts внешней PG-границей. */
    public static List<UUID> factIds(String snapshotJson) {
        return parseRefs(snapshotJson).stream().map(FactRef::id).toList();
    }

    private static double[] requireVector(FactRef ref, Map<UUID, double[]> factVectors) {
        double[] vector = factVectors.get(ref.id());
        if (vector == null) {
            throw new IllegalStateException(("факт %s слепка без embedding: селекции нужен полный backfill "
                    + "(запусти этап embed)").formatted(ref.id()));
        }
        return vector;
    }

    /** Секции в каноническом порядке; внутри секции — порядок переданного списка фактов. */
    private static String renderSections(List<FactRef> all, List<FactRef> selected) {
        Set<UUID> keep = new HashSet<>();
        for (FactRef ref : selected) {
            keep.add(ref.id());
        }
        Map<UUID, Integer> rank = new LinkedHashMap<>();
        for (int i = 0; i < selected.size(); i++) {
            rank.put(selected.get(i).id(), i);
        }
        StringBuilder sb = new StringBuilder();
        for (String section : SECTIONS) {
            List<FactRef> inSection = new ArrayList<>();
            for (FactRef ref : all) {
                if (ref.section().equals(section) && keep.contains(ref.id())) {
                    inSection.add(ref);
                }
            }
            if (inSection.isEmpty()) {
                continue;
            }
            inSection.sort(Comparator.comparingInt(ref -> rank.get(ref.id())));
            sb.append("[").append(section).append("]\n");
            for (FactRef ref : inSection) {
                sb.append("- ").append(ref.line()).append('\n');
            }
        }
        return sb.toString().strip();
    }

    private static List<FactRef> parseRefs(String snapshotJson) {
        JsonNode root;
        try {
            root = MAPPER.readTree(snapshotJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("unreadable snapshot json: " + e.getMessage(), e);
        }
        List<FactRef> refs = new ArrayList<>();
        int origin = 0;
        for (String section : SECTIONS) {
            JsonNode facts = root.path(section);
            if (!facts.isArray()) {
                continue;
            }
            for (JsonNode fact : facts) {
                String line = factLine(fact.path("statement").asText(""),
                        fact.path("subject").asText(""),
                        fact.path("predicate").asText(""),
                        fact.path("object").asText(""));
                if (line.isBlank()) {
                    continue;
                }
                String id = fact.path("id").asText("");
                if (id.isBlank()) {
                    throw new IllegalStateException("факт слепка без id: селекция джойнится на facts.embedding по id");
                }
                refs.add(new FactRef(UUID.fromString(id), section, line,
                        fact.path("reinforcement_count").asInt(0), origin++));
            }
        }
        return refs;
    }

    /** Строка дайджеста: statement, иначе SPO-склейка (тот же контракт, что у полного дайджеста). */
    public static String factLine(String statement, String subject, String predicate, String object) {
        if (statement != null && !statement.isBlank()) {
            return statement;
        }
        StringBuilder sb = new StringBuilder();
        for (String part : List.of(subject, predicate, object)) {
            if (part != null && !part.isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append(" · ");
                }
                sb.append(part);
            }
        }
        return sb.toString();
    }

    /** Косинус: числитель/знаменатель, нулевая норма — ошибка данных, не нейтральный ноль. */
    public static double cosine(double[] a, double[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException(("mixed embedding dimensions: %d vs %d — провайдер не совпадает "
                    + "с facts.embedding vector(%d)").formatted(a.length, b.length, EMBEDDING_DIMS));
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            throw new IllegalArgumentException("zero-norm embedding vector");
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /** Литерал pgvector "[0.1,0.2,...]" → вектор; NULL-колонка → null (нет эмбеддинга). */
    public static double[] parseVector(String literal) {
        if (literal == null) {
            return null;
        }
        String body = literal.strip();
        if (!body.startsWith("[") || !body.endsWith("]")) {
            throw new IllegalArgumentException("unreadable pgvector literal: " + literal);
        }
        String[] parts = body.substring(1, body.length() - 1).split(",");
        double[] vector = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Double.parseDouble(parts[i].strip());
        }
        return vector;
    }
}
