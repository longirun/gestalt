package ru.longirun.gestalt.eval.portrait;

import ru.longirun.gestalt.eval.dedup.ExactMatcher;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;

/**
 * Хранилище материализованных слепков (Р19/Р32).
 * Upsert по паре (owner_id, project_id) + O(1) чтение без LLM.
 */
public final class SnapshotStore {

    private final Connection connection;

    public SnapshotStore(Connection connection) {
        this.connection = connection;
    }

    public void upsert(String ownerId, String projectId, String snapshotJson, OffsetDateTime cutoff) throws SQLException {
        String sql = """
                INSERT INTO portrait_snapshots (owner_id, project_id, snapshot, cutoff, built_at)
                VALUES (?, ?, ?::jsonb, ?, now())
                ON CONFLICT (owner_id, project_id)
                DO UPDATE SET snapshot = EXCLUDED.snapshot,
                              cutoff = EXCLUDED.cutoff,
                              built_at = EXCLUDED.built_at
                """;

        String normOwner = ExactMatcher.normalize(ownerId);
        String normProject = ExactMatcher.normalize(projectId);
        OffsetDateTime cut = cutoff != null ? cutoff : OffsetDateTime.now();

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, normOwner);
            ps.setString(2, normProject);
            ps.setString(3, snapshotJson);
            ps.setObject(4, cut);
            ps.executeUpdate();
        }
    }
}
