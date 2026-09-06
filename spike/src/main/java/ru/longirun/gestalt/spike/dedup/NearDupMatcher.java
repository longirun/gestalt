package ru.longirun.gestalt.spike.dedup;

/**
 * Шаг 2 дедупа (Р25): trgm по subject_norm + косинус object, пороги без LLM (Р6).
 * TODO(Р34): кандидат-запросы в PG (idx_facts_subject_trgm), пороги, ведро кандидатов.
 * Embeddings опциональны (ADR 24 §9): без провайдера — деградация до trgm.
 */
public final class NearDupMatcher {

    public NearDupMatcher() {
    }
}
