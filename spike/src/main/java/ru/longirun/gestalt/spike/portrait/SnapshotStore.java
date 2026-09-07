package ru.longirun.gestalt.spike.portrait;

import ru.longirun.gestalt.spike.dedup.ExactMatcher;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Хранилище материализованных слепков (Р19/Р32).
 * Upsert по паре (owner_id, project_id) + O(1) чтение без LLM.
 */
public final class SnapshotStore {

    public record SnapshotRead(String snapshotJson, long readNanos) {
    }

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

    public Optional<SnapshotRead> get(String ownerId, String projectId) throws SQLException {
        String sql = """
                SELECT snapshot
                FROM portrait_snapshots
                WHERE owner_id = ?
                  AND project_id = ?
                LIMIT 1
                """;

        String normOwner = ExactMatcher.normalize(ownerId);
        String normProject = ExactMatcher.normalize(projectId);

        long start = System.nanoTime();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, normOwner);
            ps.setString(2, normProject);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String json = rs.getString("snapshot");
                    long duration = System.nanoTime() - start;
                    return Optional.of(new SnapshotRead(json, duration));
                }
            }
        }
        return Optional.empty();
    }

    public record BenchmarkResult(
            double p50Ms,
            double p90Ms,
            double p95Ms,
            double p99Ms,
            double avgMs,
            double minMs,
            double maxMs,
            int iterations) {
    }

    public BenchmarkResult benchmark(String ownerId, String projectId, int iterations) throws SQLException {
        long[] nanos = new long[iterations];
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            get(ownerId, projectId);
            nanos[i] = System.nanoTime() - start;
        }
        java.util.Arrays.sort(nanos);
        double p50 = nanos[(int) (iterations * 0.50)] / 1_000_000.0;
        double p90 = nanos[(int) (iterations * 0.90)] / 1_000_000.0;
        double p95 = nanos[(int) (iterations * 0.95)] / 1_000_000.0;
        double p99 = nanos[(int) (iterations * 0.99)] / 1_000_000.0;
        double min = nanos[0] / 1_000_000.0;
        double max = nanos[iterations - 1] / 1_000_000.0;
        double avg = java.util.Arrays.stream(nanos).average().orElse(0) / 1_000_000.0;
        return new BenchmarkResult(p50, p90, p95, p99, avg, min, max, iterations);
    }
}
