package ru.longirun.gestalt.spike.store;

import ru.longirun.gestalt.spike.extract.ExtractedFact;

import java.sql.Connection;
import java.util.List;

/**
 * TODO(Р34): INSERT нового факта; поиск exact-кандидата по каноническому ключу;
 * reinforcement_count++; журнал конфликтов object; выборка фактов для слепка.
 */
public final class FactRepository {

    private final Connection connection;

    public FactRepository(Connection connection) {
        this.connection = connection;
    }

    public void insert(String ownerId, String sessionId, String projectId, ExtractedFact fact) {
        throw new UnsupportedOperationException("TODO(Р34)");
    }

    public List<ru.longirun.gestalt.spike.extract.ExtractedFact> findByOwnerAndProject(String ownerId, String projectId) {
        throw new UnsupportedOperationException("TODO(Р34)");
    }
}
