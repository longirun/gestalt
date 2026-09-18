package ru.longirun.gestalt.eval.dedup;

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

    private ExactMatcher() {
    }
}
