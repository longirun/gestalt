package ru.longirun.gestalt.eval.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Реестр экспериментов (план 31 §7.1): смена ingest_fp = новый эксперимент, старый —
 * archived (read-only); переключение материалов — штатная операция, а не ручные архивы.
 * Флаги совместимости вычисляет Fingerprints (§7.2); archived-записи, зарегистрированные
 * до введения реестра, живут с NULL-fingerprint.
 */
public final class ExperimentStore {

    public record Experiment(
            String slug, String material, String datasetRef, String configSnapshot,
            String ingestFp, String answerFp, String status, String note,
            OffsetDateTime createdAt) {
    }

    private final Connection connection;

    public ExperimentStore(Connection connection) {
        this.connection = connection;
    }

    /** Регистрация/upsert: повторный вызов обновляет запись (миграция ре-раннабельна). */
    public void upsert(String slug, String material, String datasetRef, String configSnapshotJson,
                       String ingestFp, String answerFp, String status, String note) throws SQLException {
        String sql = """
                INSERT INTO experiments (slug, material, dataset_ref, config_snapshot,
                                         ingest_fp, answer_fp, status, note, created_at)
                VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, now())
                ON CONFLICT (slug)
                DO UPDATE SET material = EXCLUDED.material,
                              dataset_ref = EXCLUDED.dataset_ref,
                              config_snapshot = EXCLUDED.config_snapshot,
                              ingest_fp = EXCLUDED.ingest_fp,
                              answer_fp = EXCLUDED.answer_fp,
                              status = EXCLUDED.status,
                              note = EXCLUDED.note
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, slug);
            ps.setString(2, material);
            ps.setString(3, datasetRef);
            ps.setString(4, configSnapshotJson);
            ps.setString(5, ingestFp);
            ps.setString(6, answerFp);
            ps.setString(7, status);
            ps.setString(8, note);
            ps.executeUpdate();
        }
    }

    public Optional<Experiment> find(String slug) throws SQLException {
        String sql = "SELECT slug, material, dataset_ref, config_snapshot::text, ingest_fp, answer_fp, status, note, created_at"
                + " FROM experiments WHERE slug = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, slug);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(row(rs)) : Optional.empty();
            }
        }
    }

    public List<Experiment> byStatus(String status) throws SQLException {
        String sql = "SELECT slug, material, dataset_ref, config_snapshot::text, ingest_fp, answer_fp, status, note, created_at"
                + " FROM experiments WHERE status = ? ORDER BY created_at";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, status);
            try (ResultSet rs = ps.executeQuery()) {
                return rows(rs);
            }
        }
    }

    public List<Experiment> list() throws SQLException {
        String sql = "SELECT slug, material, dataset_ref, config_snapshot::text, ingest_fp, answer_fp, status, note, created_at"
                + " FROM experiments ORDER BY created_at";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rows(rs);
        }
    }

    private List<Experiment> rows(ResultSet rs) throws SQLException {
        List<Experiment> result = new ArrayList<>();
        while (rs.next()) {
            result.add(row(rs));
        }
        return result;
    }

    private Experiment row(ResultSet rs) throws SQLException {
        return new Experiment(
                rs.getString("slug"), rs.getString("material"), rs.getString("dataset_ref"),
                rs.getString("config_snapshot"), rs.getString("ingest_fp"), rs.getString("answer_fp"),
                rs.getString("status"), rs.getString("note"), rs.getObject("created_at", OffsetDateTime.class));
    }
}
