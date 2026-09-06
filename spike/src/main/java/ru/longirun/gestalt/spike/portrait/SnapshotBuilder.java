package ru.longirun.gestalt.spike.portrait;

import ru.longirun.gestalt.spike.extract.ExtractedFact;

import java.util.List;

/**
 * Детерминированная сборка слепка, 0 LLM (Р19). Секции: CRITICAL → CONSTRUCTS →
 * PREFERENCES (канон 14 §2); гранулярность (owner_id, project_id) — Р32.
 * TODO(Р34): правила отбора в секции, сортировка, лимит строк.
 */
public final class SnapshotBuilder {

    public String build(String ownerId, String projectId, List<ExtractedFact> facts) {
        throw new UnsupportedOperationException("TODO(Р19): CRITICAL/CONSTRUCTS/PREFERENCES");
    }
}
