package ru.longirun.gestalt.spike.extract;

import java.util.List;
import java.util.Map;

/**
 * Результат батч-экстракции. Измерения Р13: domain — о чём, scope — докуда.
 * Вид 16 §2.2: STATE (полный anchor) / NARRATIVE / EVENT.
 */
public record ExtractedFact(
        String kind,
        String subject,
        String predicate,
        String object,
        String statement,
        String domain,
        String scope,
        Map<String, String> conditions,
        List<Long> evidenceMessageIds) {

    public boolean isState() {
        return "STATE".equals(kind);
    }
}
