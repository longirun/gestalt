package ru.longirun.gestalt.spike.portrait;

import ru.longirun.gestalt.spike.store.FactRepository;
import ru.longirun.gestalt.spike.store.FactRepository.StoredFact;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Джоба пересборки слепка (Р19, Р10): cutoff-инвалидация, детерминированная сборка 0 LLM.
 */
public final class ReconciliationJob {

    private final FactRepository repository;
    private final SnapshotBuilder builder;
    private final SnapshotStore store;

    public ReconciliationJob(FactRepository repository, SnapshotBuilder builder, SnapshotStore store) {
        this.repository = repository;
        this.builder = builder;
        this.store = store;
    }

    public String reconcile(String ownerId, String projectId) throws SQLException {
        List<StoredFact> facts = repository.findByOwnerAndProject(ownerId, projectId);
        OffsetDateTime cutoff = facts.stream()
                .map(StoredFact::createdAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(OffsetDateTime.now());

        String snapshotJson = builder.build(ownerId, projectId, facts);
        store.upsert(ownerId, projectId, snapshotJson, cutoff);
        return snapshotJson;
    }
}
