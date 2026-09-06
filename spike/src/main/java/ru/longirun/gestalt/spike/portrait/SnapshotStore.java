package ru.longirun.gestalt.spike.portrait;

import java.sql.Connection;

/**
 * TODO(Р34): upsert portrait_snapshots (owner_id, project_id) + cutoff;
 * чтение O(1) одной строки (замер латентности p50/p95 — ADR 24 §8.Б).
 */
public final class SnapshotStore {

    private final Connection connection;

    public SnapshotStore(Connection connection) {
        this.connection = connection;
    }
}
