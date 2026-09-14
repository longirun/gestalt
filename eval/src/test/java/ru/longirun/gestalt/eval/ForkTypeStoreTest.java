package ru.longirun.gestalt.eval;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ru.longirun.gestalt.eval.store.ForkTypeStore;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Словарь forkType (спека 33 §6) против живого PG (gestalt_eval, localhost:5433): при
 * недоступной БД класс skip'ается (assumption). Self-cleanup — удаление только своей
 * категории (ключ test_&lt;uuid&gt;). DDL inline (как LifecycleStoreTest): полный
 * SchemaMigrator под не-owner падает на уже мигрированной БД.
 */
class ForkTypeStoreTest {

    private static final String URL = "jdbc:postgresql://localhost:5433/gestalt_eval";
    private static final String USER = "gestalt_eval";
    private static final String PASSWORD = "gestalt_eval";
    private static final String TEST_KEY = "test_" + UUID.randomUUID();

    private static Connection connection;

    @BeforeAll
    static void connect() throws Exception {
        DriverManager.setLoginTimeout(5);
        Connection conn;
        try {
            conn = DriverManager.getConnection(URL, USER, PASSWORD);
        } catch (SQLException e) {
            Assumptions.assumeTrue(false,
                    "gestalt_eval недоступна (" + e.getMessage().trim() + ") — fork_types-тесты пропущены");
            return;
        }
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS fork_types (
                        key         TEXT PRIMARY KEY,
                        description TEXT,
                        created_at  TIMESTAMPTZ NOT NULL DEFAULT now())
                    """);
        }
        connection = conn;
    }

    @AfterAll
    static void cleanup() throws SQLException {
        if (connection != null) {
            try (Statement st = connection.createStatement()) {
                st.execute("DELETE FROM fork_types WHERE key = '" + TEST_KEY + "'");
            }
            connection.close();
        }
    }

    @Test
    void seedCategoriesPresent() throws SQLException {
        ForkTypeStore store = new ForkTypeStore(connection);
        List<String> keys = store.list().stream().map(ForkTypeStore.ForkType::key).toList();
        // seed миграции 006 (спека 33 §6): категории project
        assertTrue(keys.containsAll(List.of("tech-choice", "known-pitfall", "acceptance", "rollback")),
                "seed-категории должны быть: " + keys);
    }

    @Test
    void ensureIsIdempotentAndDoesNotOverwriteDescription() throws SQLException {
        ForkTypeStore store = new ForkTypeStore(connection);
        store.ensure(TEST_KEY, "первое описание");
        store.ensure(TEST_KEY, "второе описание");
        assertTrue(store.exists(TEST_KEY));
        String description = store.list().stream()
                .filter(t -> t.key().equals(TEST_KEY))
                .findFirst().orElseThrow()
                .description();
        assertEquals("первое описание", description, "повторный ensure не перезаписывает описание");
    }

    @Test
    void existsFalseForUnknownKey() throws SQLException {
        ForkTypeStore store = new ForkTypeStore(connection);
        assertFalse(store.exists("no-such-fork-type"));
    }
}
