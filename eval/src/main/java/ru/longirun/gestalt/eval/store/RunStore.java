package ru.longirun.gestalt.eval.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Прогоны стадий (план 31 §7.1/§7.3): история попыток — в базе, а не в именах логов.
 * Резюм = новый run (старый помечен interrupted/failed), продолжающий работу с
 * чекпоинтов (replay) или существующих пар (arms). Экономика прогона — llm_calls
 * и токены — фиксируется при finish (writer-проход §7).
 */
public final class RunStore {

    /** Строка истории прогонов (команда runs). */
    public record RunRow(
            long id, String experiment, String stage, String status,
            Long llmCalls, Long promptTokens, Long completionTokens,
            OffsetDateTime startedAt, OffsetDateTime finishedAt, String note) {
    }

    /** Итог удаления прогона (§7.3): run + неперезаписанные артефакты (каскад). */
    public record RunDeletion(
            long id, String experiment, String stage, String status,
            int deletedAnswers, int deletedSnapshots) {
    }

    private final Connection connection;

    public RunStore(Connection connection) {
        this.connection = connection;
    }

    public long start(String experiment, String stage, String note) throws SQLException {
        String sql = "INSERT INTO runs (experiment, stage, status, note) VALUES (?, ?, 'running', ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, experiment);
            ps.setString(2, stage);
            ps.setString(3, note);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (!rs.next()) {
                    throw new SQLException("no generated key for run");
                }
                return rs.getLong(1);
            }
        }
    }

    public void finish(long runId, String status) throws SQLException {
        finish(runId, status, null, null, null);
    }

    /** Финализация с экономикой прогона (NULL — стадия без LLM). */
    public void finish(long runId, String status,
                       Long llmCalls, Long promptTokens, Long completionTokens) throws SQLException {
        String sql = "UPDATE runs SET status = ?, finished_at = now(),"
                + " llm_calls = ?, prompt_tokens = ?, completion_tokens = ? WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, status);
            setNullableLong(ps, 2, llmCalls);
            setNullableLong(ps, 3, promptTokens);
            setNullableLong(ps, 4, completionTokens);
            ps.setLong(5, runId);
            ps.executeUpdate();
        }
    }

    /** Застрявшие running-прогоны стадии = убитые процессы: помечаем interrupted.
     *  Вызывается перед start нового run той же стадии. Возвращаем сколько помечено. */
    public int interruptStale(String experiment, String stage) throws SQLException {
        String sql = "UPDATE runs SET status = 'interrupted', finished_at = now()"
                + " WHERE experiment = ? AND stage = ? AND status = 'running'";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, experiment);
            ps.setString(2, stage);
            return ps.executeUpdate();
        }
    }

    /** История прогонов эксперимента по возрастанию id. */
    public List<RunRow> list(String experiment) throws SQLException {
        String sql = """
                SELECT id, experiment, stage, status, llm_calls, prompt_tokens, completion_tokens,
                       started_at, finished_at, note
                FROM runs WHERE experiment = ? ORDER BY id
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, experiment);
            try (ResultSet rs = ps.executeQuery()) {
                List<RunRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new RunRow(
                            rs.getLong("id"), rs.getString("experiment"), rs.getString("stage"),
                            rs.getString("status"), getNullableLong(rs, "llm_calls"),
                            getNullableLong(rs, "prompt_tokens"), getNullableLong(rs, "completion_tokens"),
                            rs.getObject("started_at", OffsetDateTime.class),
                            rs.getObject("finished_at", OffsetDateTime.class), rs.getString("note")));
                }
                return rows;
            }
        }
    }

    /**
     * Удаление прогона (§7.3 — штатная операция): run + записанные им и не перезаписанные
     * позже артефакты (answers.run_id, snapshots.run_id — FK CASCADE; перезаписанные позже
     * ответы/слепки несут чужой run_id и выживают). Возвращает итог для печати.
     */
    public RunDeletion delete(long runId) throws SQLException {
        String meta = "SELECT experiment, stage, status FROM runs WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(meta)) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("run #%d не найден".formatted(runId));
                }
                int answers = count("SELECT count(*) FROM answers WHERE run_id = ?", runId);
                int snapshots = count("SELECT count(*) FROM snapshots WHERE run_id = ?", runId);
                String experiment = rs.getString(1);
                String stage = rs.getString(2);
                String status = rs.getString(3);
                try (PreparedStatement del = connection.prepareStatement("DELETE FROM runs WHERE id = ?")) {
                    del.setLong(1, runId);
                    del.executeUpdate();
                }
                return new RunDeletion(runId, experiment, stage, status, answers, snapshots);
            }
        }
    }

    private int count(String sql, long runId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private static Long getNullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
