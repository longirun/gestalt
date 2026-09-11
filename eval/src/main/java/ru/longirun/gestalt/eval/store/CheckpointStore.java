package ru.longirun.gestalt.eval.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Чекпоинты replay (Р36 — кэш инжеста): сессия лога → последняя обработанная реплика.
 * GREATEST в upsert защищает от отката курсора назад при повторных прогонах.
 */
public final class CheckpointStore {

    private final Connection connection;

    public CheckpointStore(Connection connection) {
        this.connection = connection;
    }

    public long lastProcessedMessageId(String sessionId) throws SQLException {
        String sql = "SELECT last_message_id FROM replay_checkpoints WHERE session_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    public void advance(String sessionId, long messageId) throws SQLException {
        String sql = """
                INSERT INTO replay_checkpoints (session_id, last_message_id, updated_at)
                VALUES (?, ?, now())
                ON CONFLICT (session_id)
                DO UPDATE SET last_message_id = GREATEST(replay_checkpoints.last_message_id, EXCLUDED.last_message_id),
                              updated_at = now()
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setLong(2, messageId);
            ps.executeUpdate();
        }
    }
}
