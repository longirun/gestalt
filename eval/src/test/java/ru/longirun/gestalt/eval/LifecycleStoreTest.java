package ru.longirun.gestalt.eval;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ru.longirun.gestalt.eval.store.ExperimentStore;
import ru.longirun.gestalt.eval.store.FactRepository;
import ru.longirun.gestalt.eval.store.PointSnapshotStore;
import ru.longirun.gestalt.eval.store.ResultStore;
import ru.longirun.gestalt.eval.store.RunStore;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lifecycle-хранилища (§7.1) против живого PG (gestalt_eval, localhost:5433): при
 * недоступной БД класс skip'ается (assumption). Self-cleanup — удаление только своего
 * эксперимента (slug test_&lt;uuid&gt;) каскадом по FK. DDL — inline (как CheckpointStoreTest):
 * полный SchemaMigrator под не-owner падает на уже мигрированной БД.
 */
class LifecycleStoreTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String URL = "jdbc:postgresql://localhost:5433/gestalt_eval";
    private static final String USER = "gestalt_eval";
    private static final String PASSWORD = "gestalt_eval";
    private static final String SLUG = "test_" + UUID.randomUUID();

    private static Connection connection;

    @BeforeAll
    static void connect() throws Exception {
        DriverManager.setLoginTimeout(5);
        Connection conn;
        try {
            conn = DriverManager.getConnection(URL, USER, PASSWORD);
        } catch (SQLException e) {
            Assumptions.assumeTrue(false,
                    "gestalt_eval недоступна (" + e.getMessage().trim() + ") — lifecycle-тесты пропущены");
            return;
        }
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS experiments (
                        slug            TEXT PRIMARY KEY,
                        material        TEXT NOT NULL,
                        dataset_ref     TEXT,
                        config_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
                        ingest_fp       TEXT,
                        answer_fp       TEXT,
                        status          TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','archived')),
                        note            TEXT,
                        created_at      TIMESTAMPTZ NOT NULL DEFAULT now())
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS runs (
                        id                BIGSERIAL PRIMARY KEY,
                        experiment        TEXT NOT NULL REFERENCES experiments(slug) ON DELETE CASCADE,
                        stage             TEXT NOT NULL CHECK (stage IN ('replay','arms','oracles','wcheck','report')),
                        status            TEXT NOT NULL DEFAULT 'running' CHECK (status IN ('running','done','failed','interrupted')),
                        llm_calls         BIGINT,
                        prompt_tokens     BIGINT,
                        completion_tokens BIGINT,
                        started_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
                        finished_at       TIMESTAMPTZ,
                        note              TEXT)
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS answers (
                        experiment        TEXT NOT NULL REFERENCES experiments(slug) ON DELETE CASCADE,
                        point_id          TEXT NOT NULL,
                        arm               TEXT NOT NULL CHECK (arm IN ('a','b')),
                        point_fp          TEXT,
                        answer            TEXT NOT NULL,
                        model             TEXT,
                        tokens            BIGINT,
                        prompt_tokens     BIGINT,
                        completion_tokens BIGINT,
                        latency_ms        BIGINT,
                        run_id            BIGINT REFERENCES runs(id) ON DELETE CASCADE,
                        at                TIMESTAMPTZ NOT NULL DEFAULT now(),
                        PRIMARY KEY (experiment, point_id, arm))
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS verdicts (
                        experiment  TEXT NOT NULL REFERENCES experiments(slug) ON DELETE CASCADE,
                        point_id    TEXT NOT NULL,
                        a_pass      BOOLEAN,
                        b_pass      BOOLEAN,
                        leak        BOOLEAN,
                        payload     JSONB NOT NULL,
                        at          TIMESTAMPTZ NOT NULL DEFAULT now(),
                        PRIMARY KEY (experiment, point_id))
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS wchecks (
                        experiment     TEXT NOT NULL REFERENCES experiments(slug) ON DELETE CASCADE,
                        point_id       TEXT NOT NULL,
                        covered        BOOLEAN,
                        answer_covered BOOLEAN,
                        payload        JSONB NOT NULL,
                        at             TIMESTAMPTZ NOT NULL DEFAULT now(),
                        PRIMARY KEY (experiment, point_id))
                    """);
            // 005: пер-точечные слепки (inline — как SchemaMigrator)
            st.execute("""
                    CREATE TABLE IF NOT EXISTS snapshots (
                        experiment TEXT NOT NULL REFERENCES experiments(slug) ON DELETE CASCADE,
                        point_id   TEXT NOT NULL,
                        kind       TEXT NOT NULL CHECK (kind IN ('m','w0')),
                        payload    TEXT NOT NULL,
                        run_id     BIGINT REFERENCES runs(id) ON DELETE CASCADE,
                        at         TIMESTAMPTZ NOT NULL DEFAULT now(),
                        PRIMARY KEY (experiment, point_id, kind))
                    """);
        }
        connection = conn;
    }

    @AfterAll
    static void cleanup() throws SQLException {
        if (connection == null) {
            return;
        }
        try (var ps = connection.prepareStatement(
                "DELETE FROM experiments WHERE slug IN (?, ?, ?)")) {
            ps.setString(1, SLUG);
            ps.setString(2, SLUG + "_2");
            ps.setString(3, SLUG + "_3");
            ps.executeUpdate();
        } finally {
            connection.close();
        }
    }

    @Test
    void experimentUpsertFindAndList() throws Exception {
        ExperimentStore store = new ExperimentStore(connection);
        store.upsert(SLUG, "lme", "dataset/x.jsonl", "{\"window.size\":5}",
                "ingest-fp-1", "answer-fp-1", "active", "тест");
        assertTrue(store.find(SLUG).isPresent());
        assertEquals("active", store.find(SLUG).orElseThrow().status());
        assertEquals(1, store.byStatus("active").stream().filter(e -> e.slug().equals(SLUG)).count());

        // upsert обновляет, не дублирует
        store.upsert(SLUG, "lme", "dataset/x.jsonl", "{}", "ingest-fp-2", null, "archived", "архив");
        assertEquals(1, store.list().stream().filter(e -> e.slug().equals(SLUG)).count());
        assertEquals("archived", store.find(SLUG).orElseThrow().status());
        assertEquals("ingest-fp-2", store.find(SLUG).orElseThrow().ingestFp());
    }

    @Test
    void runLifecycleAndArtifactRoundtrip() throws Exception {
        ExperimentStore experiments = new ExperimentStore(connection);
        RunStore runs = new RunStore(connection);
        ResultStore results = new ResultStore(connection);
        experiments.upsert(SLUG, "lme", null, "{}", null, null, "active", null);

        long runId = runs.start(SLUG, "wcheck", "self-test");
        runs.finish(runId, "done");
        assertEquals("done", runs.list(SLUG).getFirst().status());

        results.upsertAnswer(SLUG, "P1", "a", "pf-p1", "ответ A", "m", 10L, 7L, 3L, 120L, runId,
                OffsetDateTime.now());
        results.upsertAnswer(SLUG, "P1", "b", "pf-p1", "ответ B", "m", 11L, 8L, 3L, 100L, runId,
                OffsetDateTime.now());
        // перезапись ответа тем же ключом — upsert, не дубль
        results.upsertAnswer(SLUG, "P1", "a", "pf-p1v2", "ответ A v2", "m2", 12L, 8L, 4L, 90L, runId,
                OffsetDateTime.now());
        List<ResultStore.AnswerRow> answers = results.answers(SLUG);
        assertEquals(2, answers.size());
        assertEquals("ответ A v2", answers.stream()
                .filter(r -> r.arm().equals("a")).findFirst().orElseThrow().answer());
        assertEquals("pf-p1v2", answers.stream()
                .filter(r -> r.arm().equals("a")).findFirst().orElseThrow().pointFp(),
                "перезапись строки нового поколения обновляет и point_fp");

        results.upsertVerdict(SLUG, "P1", true, false, false,
                "{\"pointId\":\"P1\",\"a\":{\"pass\":true},\"b\":{\"pass\":false}}");
        results.upsertWCheck(SLUG, "P1", true, false,
                "{\"pointId\":\"P1\",\"covered\":true,\"answerCovered\":false}");
        assertEquals(1, results.verdicts(SLUG).size());
        assertEquals("P1", results.verdicts(SLUG).getFirst().pointId());
        // payload хранится как jsonb: порядок ключей/пробелы нормализуются PG — проверяем парсингом
        assertTrue(MAPPER.readTree(results.wchecks(SLUG).getFirst().payloadJson())
                .path("covered").asBoolean());
    }

    @Test
    void runEconomyInterruptStaleAndList() throws Exception {
        ExperimentStore experiments = new ExperimentStore(connection);
        RunStore runs = new RunStore(connection);
        experiments.upsert(SLUG, "lme", null, "{}", null, null, "active", null);

        long done = runs.start(SLUG, "replay", "экономика");
        runs.finish(done, "done", 7L, 1_000L, 500L);
        long running = runs.start(SLUG, "replay", "застрянет");
        long otherStage = runs.start(SLUG, "arms", "не трогать");

        assertEquals(1, runs.interruptStale(SLUG, "replay"), "один застрявший replay");
        assertEquals(0, runs.interruptStale(SLUG, "replay"), "повторно — нечего помечать");

        RunStore.RunRow doneRow = runs.list(SLUG).stream()
                .filter(r -> r.id() == done).findFirst().orElseThrow();
        assertEquals("done", doneRow.status());
        assertEquals(7L, doneRow.llmCalls());
        assertEquals(1_000L, doneRow.promptTokens());
        assertEquals(500L, doneRow.completionTokens());
        assertEquals("interrupted", runs.list(SLUG).stream()
                .filter(r -> r.id() == running).findFirst().orElseThrow().status());
        assertEquals("running", runs.list(SLUG).stream()
                .filter(r -> r.id() == otherStage).findFirst().orElseThrow().status(),
                "interruptStale не трогает другие стадии");
    }

    @Test
    void snapshotAndAnswerCascadeOnRunDelete() throws Exception {
        ExperimentStore experiments = new ExperimentStore(connection);
        RunStore runs = new RunStore(connection);
        ResultStore results = new ResultStore(connection);
        PointSnapshotStore snapshots = new PointSnapshotStore(connection);
        experiments.upsert(SLUG, "lme", null, "{}", null, null, "active", null);

        long run1 = runs.start(SLUG, "replay", "первый");
        long run2 = runs.start(SLUG, "arms", "перезапишет часть");
        results.upsertAnswer(SLUG, "P9", "a", "pf-p9", "A v1", "m", 1L, 1L, 1L, 1L, run1, OffsetDateTime.now());
        results.upsertAnswer(SLUG, "P9", "b", "pf-p9", "B v1", "m", 1L, 1L, 1L, 1L, run1, OffsetDateTime.now());
        snapshots.upsert(SLUG, "P9", "m", "{\"v\":1}", run1);
        snapshots.upsert(SLUG, "P9", "w0", "{\"v\":1}", run1);

        // run2 перезаписывает ответ A и M-слепок (run_id авторства переходит к run2)
        results.upsertAnswer(SLUG, "P9", "a", "pf-p9", "A v2", "m", 2L, 2L, 2L, 2L, run2, OffsetDateTime.now());
        snapshots.upsert(SLUG, "P9", "m", "{\"v\":2}", run2);

        assertTrue(snapshots.exists(SLUG, "P9", "w0"));
        assertTrue(snapshots.find(SLUG, "P9", "m").orElseThrow().contains("\"v\":2"));

        RunStore.RunDeletion deleted = runs.delete(run1);
        assertEquals(run1, deleted.id());
        assertEquals(1, deleted.deletedAnswers(), "P9.b удалён, P9.a перезаписан run2");
        assertEquals(1, deleted.deletedSnapshots(), "w0 удалён, m перезаписан run2");

        List<ResultStore.AnswerRow> answers = results.answers(SLUG);
        assertEquals(1, answers.size());
        assertEquals("A v2", answers.getFirst().answer());
        assertEquals(run2, answers.getFirst().runId());
        assertTrue(snapshots.find(SLUG, "P9", "m").isPresent(), "M-слепок выжил (автор run2)");
        assertTrue(snapshots.find(SLUG, "P9", "w0").isEmpty(), "W0 удалён с автором run1");

        RunStore.RunDeletion deleted2 = runs.delete(run2);
        assertEquals(1, deleted2.deletedAnswers());
        assertEquals(1, deleted2.deletedSnapshots());
        assertTrue(results.answers(SLUG).isEmpty());
        assertTrue(snapshots.list(SLUG).isEmpty());
    }

    /**
     * Протокол replay-эксперимента (§7.2): продолжение при совпадении ingest_fp,
     * громкий отказ при смене fp и записи в archived. Ветку создания (архивирует
     * все active) на живой БД не проверяем — она деструктивна для реального реестра.
     */
    @Test
    void experimentsForReplayProtocol() throws Exception {
        EvalConfig config = new EvalConfig(
                "jdbc:postgresql://localhost:5433/honcho_memory", "u", "p",
                "2026-06-15", "2026-08-11", "", "src/test/resources/lme/tiny.json",
                "jdbc:postgresql://localhost:5433/gestalt_eval", "u", "p",
                "https://llm", "key", "qwen", "low",
                "https://llm", "key", "", "low",
                "", "", "",
                20, 2000, 5, 50, "user:t", "project:t", "dataset/points.jsonl");
        List<EvalPoint> points = List.of(new EvalPoint("p1", "L1", "lme-abc", 10,
                "t", "tr", List.of(), List.of(), null, null, null, null));
        String ingestFp = Fingerprints.ingestFp(config, points);
        ExperimentStore experiments = new ExperimentStore(connection);

        // продолжение: fp совпадает
        experiments.upsert(SLUG + "_2", "lme", null, "{}", ingestFp, null, "active", null);
        assertEquals(SLUG + "_2", Experiments.forReplay(connection, config, points, SLUG + "_2"));

        // смена fp = громкий отказ без действий (новый эксперимент — явное действие владельца)
        experiments.upsert(SLUG + "_3", "lme", null, "{}", "stale-fp", null, "active", null);
        assertThrows(IllegalStateException.class,
                () -> Experiments.forReplay(connection, config, points, SLUG + "_3"));

        // archived read-only
        experiments.upsert(SLUG + "_2", "lme", null, "{}", ingestFp, null, "archived", null);
        assertThrows(IllegalStateException.class,
                () -> Experiments.forReplay(connection, config, points, SLUG + "_2"));
    }

    @Test
    void factRepositoryFindKnownPredicates() throws Exception {
        FactRepository repo = new FactRepository(connection);
        String testProject = "project:test_predicates_" + UUID.randomUUID();
        assertTrue(repo.findKnownPredicates(testProject, 200).isEmpty());

        try (Statement st = connection.createStatement()) {
            st.execute("INSERT INTO facts (owner_id, session_id, project_id, fact_domain, scope, kind, subject_norm, predicate_norm, object_value) VALUES "
                    + "('user:t', 's1', '" + testProject + "', 'WORLD', 'PROJECT', 'STATE', 'env:test', 'host_ip', '192.0.2.1'),"
                    + "('user:t', 's1', '" + testProject + "', 'WORLD', 'PROJECT', 'STATE', 'env:test', 'ssh_user', 'deployer'),"
                    + "('user:t', 's1', '" + testProject + "', 'WORLD', 'PROJECT', 'STATE', 'env:test', 'host_ip', '192.0.2.2')");
        }

        try {
            List<String> preds = repo.findKnownPredicates(testProject, 200);
            assertEquals(List.of("host_ip", "ssh_user"), preds);
        } finally {
            try (Statement st = connection.createStatement()) {
                st.execute("DELETE FROM facts WHERE project_id = '" + testProject + "'");
            }
        }
    }
}
