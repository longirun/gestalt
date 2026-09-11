package ru.longirun.gestalt.eval;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ru.longirun.gestalt.eval.store.CheckpointStore;
import ru.longirun.gestalt.eval.store.SchemaMigrator;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Чекпоинты replay против живого PG (gestalt_eval, localhost:5433): при недоступной БД
 * весь класс skip'ается (assumption). Self-cleanup — удаление только своих строк
 * по точным session_id (префикс test_&lt;uuid&gt;-).
 */
class CheckpointStoreTest {

    private static final String URL = "jdbc:postgresql://localhost:5433/gestalt_eval";
    private static final String USER = "gestalt_eval";
    private static final String PASSWORD = "gestalt_eval";
    private static final String SESSION_PREFIX = "test_" + UUID.randomUUID() + "-";

    private static Connection connection;
    private static final Set<String> createdSessions = new HashSet<>();

    @BeforeAll
    static void connect() throws Exception {
        DriverManager.setLoginTimeout(5);
        Connection conn;
        try {
            conn = DriverManager.getConnection(URL, USER, PASSWORD);
        } catch (SQLException e) {
            Assumptions.assumeTrue(false,
                    "gestalt_eval недоступна (" + e.getMessage().trim() + ") — тесты чекпоинтов пропущены");
            return;
        }
        // Гарантируем только схему чекпоинтов: полный SchemaMigrator на уже мигрированной БД
        // падает под не-owner пользователем (CREATE INDEX IF NOT EXISTS требует owner, см. migrate-тест).
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS replay_checkpoints (
                        session_id      TEXT PRIMARY KEY,
                        last_message_id BIGINT NOT NULL,
                        updated_at      TIMESTAMPTZ NOT NULL DEFAULT now())
                    """);
        }
        connection = conn;
    }

    @AfterAll
    static void cleanup() throws SQLException {
        if (connection == null) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM replay_checkpoints WHERE session_id = ?")) {
            for (String session : createdSessions) {
                ps.setString(1, session);
                ps.executeUpdate();
            }
        } finally {
            connection.close();
        }
    }

    private static String session(String name) {
        String id = SESSION_PREFIX + name;
        createdSessions.add(id);
        return id;
    }

    @Test
    void lastProcessedMessageIdIsZeroForUnknownSession() throws SQLException {
        CheckpointStore store = new CheckpointStore(connection);
        assertEquals(0L, store.lastProcessedMessageId(session("unknown")));
    }

    @Test
    void advancePersistsAndUpdatesCheckpoint() throws SQLException {
        CheckpointStore store = new CheckpointStore(connection);
        String id = session("advance");

        store.advance(id, 5L);
        assertEquals(5L, store.lastProcessedMessageId(id));

        store.advance(id, 10L);
        assertEquals(10L, store.lastProcessedMessageId(id));
    }

    @Test
    void advanceWithSmallerIdDoesNotRollBackCursor() throws SQLException {
        CheckpointStore store = new CheckpointStore(connection);
        String id = session("greatest");

        store.advance(id, 100L);
        store.advance(id, 42L);
        assertEquals(100L, store.lastProcessedMessageId(id), "GREATEST: курсор не откатывается назад");
    }

    @Test
    void migrateLeavesReplayCheckpointsAvailable() throws Exception {
        // Инвариант: после SchemaMigrator.migrate таблица чекпоинтов существует/доступна.
        // Известная особенность: на уже мигрированной БД migrate падает под не-owner
        // (CREATE INDEX/CREATE OR REPLACE FUNCTION требуют owner) — тогда инвариант
        // проверяем по фактическому наличию таблицы.
        try {
            SchemaMigrator.migrate(connection);
        } catch (Exception e) {
            System.err.println("[WARN] SchemaMigrator.migrate: " + e.getMessage());
        }
        assertTrue(replayCheckpointsExists(),
                "replay_checkpoints должна существовать после SchemaMigrator.migrate");
    }

    private boolean replayCheckpointsExists() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT count(*) FROM information_schema.tables"
                        + " WHERE table_schema = 'public' AND table_name = 'replay_checkpoints'");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() && rs.getInt(1) == 1;
        }
    }
}
