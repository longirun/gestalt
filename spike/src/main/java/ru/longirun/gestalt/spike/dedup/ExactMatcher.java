package ru.longirun.gestalt.spike.dedup;

import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Шаг 1 дедупа (Р23, канон 16 §4.1): точная идентичность STATE-факта по
 * (owner_id, scope, project_id, subject_norm, predicate_norm, conditions).
 * Object не входит: новое значение якоря — событие изменения, не дубль.
 */
public final class ExactMatcher {

    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    public static String canonicalKey(String ownerId,
                                      String scope,
                                      String projectId,
                                      String subject,
                                      String predicate,
                                      Map<String, String> conditions) {
        Map<String, String> sorted = conditions == null ? Map.of() : new TreeMap<>(conditions);
        String conditionsKey = sorted.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(";"));
        return String.join("|",
                normalize(ownerId),
                normalize(scope),
                normalize(projectId),
                normalize(subject),
                normalize(predicate),
                conditionsKey);
    }

    private ExactMatcher() {
    }
}
