package ru.longirun.gestalt.eval.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Пер-точечные слепки replay (план 31 §7.5, writer-проход): канон в PG — portrait_snapshots
 * хранит только последний слепок на пару owner+project, история M/W0-проб точек раньше
 * жила лишь в out/snapshots. Payload — каноническая строка слепка байт-в-байт (TEXT);
 * run_id — автор записи для каскадного удаления прогона (перезаписанные переживают).
 */
public final class PointSnapshotStore {

    /** Слепок точки эксперимента: kind = m | w0. */
    public record SnapshotRef(String pointId, String kind) {

        /** Имя файла-дампа out/snapshots (контракт viewer): <pointId>[.w0].json. */
        public String fileName() {
            return pointId + ("w0".equals(kind) ? ".w0.json" : ".json");
        }
    }

    private final Connection connection;

    public PointSnapshotStore(Connection connection) {
        this.connection = connection;
    }

    public void upsert(String experiment, String pointId, String kind,
                       String payload, Long runId) throws SQLException {
        String sql = """
                INSERT INTO snapshots (experiment, point_id, kind, payload, run_id)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (experiment, point_id, kind)
                DO UPDATE SET payload = EXCLUDED.payload, run_id = EXCLUDED.run_id, at = now()
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, experiment);
            ps.setString(2, pointId);
            ps.setString(3, kind);
            ps.setString(4, payload);
            if (runId == null) {
                ps.setNull(5, java.sql.Types.BIGINT);
            } else {
                ps.setLong(5, runId);
            }
            ps.executeUpdate();
        }
    }

    public Optional<String> find(String experiment, String pointId, String kind) throws SQLException {
        String sql = "SELECT payload FROM snapshots WHERE experiment = ? AND point_id = ? AND kind = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, experiment);
            ps.setString(2, pointId);
            ps.setString(3, kind);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    public boolean exists(String experiment, String pointId, String kind) throws SQLException {
        String sql = "SELECT 1 FROM snapshots WHERE experiment = ? AND point_id = ? AND kind = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, experiment);
            ps.setString(2, pointId);
            ps.setString(3, kind);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Все слепки эксперимента (список viewer'а / аудит полноты). */
    public List<SnapshotRef> list(String experiment) throws SQLException {
        String sql = "SELECT point_id, kind FROM snapshots WHERE experiment = ? ORDER BY point_id, kind";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, experiment);
            try (ResultSet rs = ps.executeQuery()) {
                List<SnapshotRef> refs = new ArrayList<>();
                while (rs.next()) {
                    refs.add(new SnapshotRef(rs.getString(1), rs.getString(2)));
                }
                return refs;
            }
        }
    }
}
