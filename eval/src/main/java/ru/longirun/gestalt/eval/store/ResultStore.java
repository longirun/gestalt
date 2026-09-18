package ru.longirun.gestalt.eval.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Пер-точечные результаты эксперимента (план 31 §7.1): answers (контракт A/B плеч),
 * verdicts (оракулы E4), wchecks (W-проверка E2). Payload-колонки хранят канонический
 * json артефакта — viewer и отчёт читают БД, out/ остаётся стираемым дампом.
 */
public final class ResultStore {

    /** Ответ плеча: поля контракта answers (план 31 §3); point_fp — валидность строки (Fingerprints.pointFp). */
    public record AnswerRow(
            String pointId, String arm, String pointFp, String answer, String model,
            Long tokens, Long promptTokens, Long completionTokens,
            Long latencyMs, Long runId, OffsetDateTime at) {
    }

    public record VerdictRow(String pointId, String payloadJson) {
    }

    public record WCheckRow(String pointId, String payloadJson) {
    }

    private final Connection connection;

    public ResultStore(Connection connection) {
        this.connection = connection;
    }

    public void upsertAnswer(String experiment, String pointId, String arm, String pointFp, String answer,
                             String model, Long tokens, Long promptTokens, Long completionTokens,
                             Long latencyMs, Long runId, OffsetDateTime at) throws SQLException {
        String sql = """
                INSERT INTO answers (experiment, point_id, arm, point_fp, answer, model, tokens,
                                     prompt_tokens, completion_tokens, latency_ms, run_id, at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (experiment, point_id, arm)
                DO UPDATE SET point_fp = EXCLUDED.point_fp, answer = EXCLUDED.answer,
                              model = EXCLUDED.model, tokens = EXCLUDED.tokens,
                              prompt_tokens = EXCLUDED.prompt_tokens,
                              completion_tokens = EXCLUDED.completion_tokens,
                              latency_ms = EXCLUDED.latency_ms,
                              run_id = EXCLUDED.run_id, at = EXCLUDED.at
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, experiment);
            ps.setString(2, pointId);
            ps.setString(3, arm);
            ps.setString(4, pointFp);
            ps.setString(5, answer);
            ps.setString(6, model);
            Sql.setNullableLong(ps, 7, tokens);
            Sql.setNullableLong(ps, 8, promptTokens);
            Sql.setNullableLong(ps, 9, completionTokens);
            Sql.setNullableLong(ps, 10, latencyMs);
            if (runId == null) {
                ps.setNull(11, java.sql.Types.BIGINT);
            } else {
                ps.setLong(11, runId);
            }
            ps.setObject(12, at);
            ps.executeUpdate();
        }
    }

    public void upsertVerdict(String experiment, String pointId,
                              Boolean aPass, Boolean bPass, Boolean leak,
                              String payloadJson) throws SQLException {
        String sql = """
                INSERT INTO verdicts (experiment, point_id, a_pass, b_pass, leak, payload)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (experiment, point_id)
                DO UPDATE SET a_pass = EXCLUDED.a_pass, b_pass = EXCLUDED.b_pass,
                              leak = EXCLUDED.leak, payload = EXCLUDED.payload, at = now()
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, experiment);
            ps.setString(2, pointId);
            ps.setObject(3, aPass);
            ps.setObject(4, bPass);
            ps.setObject(5, leak);
            ps.setString(6, payloadJson);
            ps.executeUpdate();
        }
    }

    public void upsertWCheck(String experiment, String pointId,
                             Boolean covered, Boolean answerCovered,
                             String payloadJson) throws SQLException {
        String sql = """
                INSERT INTO wchecks (experiment, point_id, covered, answer_covered, payload)
                VALUES (?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (experiment, point_id)
                DO UPDATE SET covered = EXCLUDED.covered, answer_covered = EXCLUDED.answer_covered,
                              payload = EXCLUDED.payload, at = now()
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, experiment);
            ps.setString(2, pointId);
            ps.setObject(3, covered);
            ps.setObject(4, answerCovered);
            ps.setString(5, payloadJson);
            ps.executeUpdate();
        }
    }

    /**
     * Снос всех ответов эксперимента (§7.2 rewrite): ответы чужого answer_fp не имеют права
     * оставаться — lift/проценты считаются по смешению поколений. Возвращает число удалённых строк.
     */
    public int deleteAnswers(String experiment) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM answers WHERE experiment = ?")) {
            ps.setString(1, experiment);
            return ps.executeUpdate();
        }
    }

    /** Все ответы эксперимента, отсортированные по (point_id, arm). */
    public List<AnswerRow> answers(String experiment) throws SQLException {
        String sql = """
                SELECT point_id, arm, point_fp, answer, model, tokens, prompt_tokens, completion_tokens,
                       latency_ms, run_id, at
                FROM answers WHERE experiment = ? ORDER BY point_id, arm
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, experiment);
            try (ResultSet rs = ps.executeQuery()) {
                List<AnswerRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new AnswerRow(
                            rs.getString("point_id"), rs.getString("arm"), rs.getString("point_fp"),
                            rs.getString("answer"), rs.getString("model"),
                            Sql.getNullableLong(rs, "tokens"),
                            Sql.getNullableLong(rs, "prompt_tokens"), Sql.getNullableLong(rs, "completion_tokens"),
                            Sql.getNullableLong(rs, "latency_ms"),
                            Sql.getNullableLong(rs, "run_id"), rs.getObject("at", OffsetDateTime.class)));
                }
                return rows;
            }
        }
    }

    public List<VerdictRow> verdicts(String experiment) throws SQLException {
        String sql = "SELECT point_id, payload::text FROM verdicts WHERE experiment = ? ORDER BY point_id";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, experiment);
            try (ResultSet rs = ps.executeQuery()) {
                List<VerdictRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new VerdictRow(rs.getString(1), rs.getString(2)));
                }
                return rows;
            }
        }
    }

    public List<WCheckRow> wchecks(String experiment) throws SQLException {
        String sql = "SELECT point_id, payload::text FROM wchecks WHERE experiment = ? ORDER BY point_id";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, experiment);
            try (ResultSet rs = ps.executeQuery()) {
                List<WCheckRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new WCheckRow(rs.getString(1), rs.getString(2)));
                }
                return rows;
            }
        }
    }

}
