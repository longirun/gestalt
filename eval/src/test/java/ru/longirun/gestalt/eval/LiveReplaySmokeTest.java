package ru.longirun.gestalt.eval;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.longirun.gestalt.eval.ingest.RawMessage;
import ru.longirun.gestalt.eval.store.CheckpointStore;
import ru.longirun.gestalt.eval.store.ExperimentStore;
import ru.longirun.gestalt.eval.store.RunStore;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Репетиция Live-телеметрии replay (E8) без LLM: поднимает одноразовый эксперимент
 * test_live_&lt;uuid&gt;, крутит реальные записи runs.progress + checkpoints.advance и
 * консольный \r-лайн printProgress — viewer (таб «replay live», ?exp=test_live_...)
 * в это время показывает движение: прогресс, скорость, ETA. Ничего реального не
 * трогает: эксперимент удаляется каскадом (run вместе с ним); курсор чекпоинта
 * уезжает вперёд — безвредно (GREATEST не даёт откатиться, новый эксперимент
 * всё равно начинается с checkpoints.reset). Тег "viewer" — из дефолтного
 * gradlew test исключён: запускать явно, стоп = Ctrl+C.
 */
@Tag("viewer")
class LiveReplaySmokeTest {

    private static final String URL = "jdbc:postgresql://localhost:5433/gestalt_eval";
    private static final String USER = "gestalt_eval";
    private static final String PASSWORD = "gestalt_eval";
    private static final String SESSION = "per-directory-opencode-sample-opencode";
    private static final long ID_FROM = 1000;
    private static final long ID_TO = 6000;
    private static final int TICKS = 60;
    private static final long TICK_MS = 500;

    @Test
    void liveTelemetryRehearsal() throws Exception {
        DriverManager.setLoginTimeout(5);
        Connection conn;
        try {
            conn = DriverManager.getConnection(URL, USER, PASSWORD);
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "gestalt_eval недоступна — репетиция пропущена");
            return;
        }
        String slug = "test_live_" + UUID.randomUUID();
        int synthTotal = 6209;
        List<RawMessage> log = new ArrayList<>();
        for (long i = 0; i < synthTotal; i++) {
            log.add(new RawMessage(ID_FROM + i * (ID_TO - ID_FROM) / synthTotal,
                    SESSION, "peer", "msg " + i, 100, OffsetDateTime.now()));
        }

        ExperimentStore experiments = new ExperimentStore(conn);
        experiments.upsert(slug, "smoke", null, "{}", null, null, "active", "live-телеметрия: репетиция");
        RunStore runs = new RunStore(conn);
        CheckpointStore checkpoints = new CheckpointStore(conn);
        // GREATEST в advance не даёт двигать курсор вниз: реальный курсор сессии уже
        // в конце лога — репетиция сбрасывает его и восстанавливает в finally
        long savedCursor = checkpoints.lastProcessedMessageId("session:" + SESSION);
        checkpoints.reset("session:" + SESSION);
        long runId = runs.start(slug, "replay", "репетиция live-телеметрии (без LLM)");
        EvalRunner.Metrics metrics = new EvalRunner.Metrics();
        long startedNanos = System.nanoTime();
        System.out.println("[SMOKE] experiment " + slug + ", run #" + runId
                + " — смотрите таб «replay live» (?exp=" + slug + ")");
        try {
            long step = (ID_TO - ID_FROM) / TICKS;
            for (int tick = 1; tick <= TICKS; tick++) {
                long cursor = ID_FROM + step * tick;
                metrics.llmCalls++;
                metrics.promptTokens += 3_000;
                metrics.completionTokens += 700;
                checkpoints.advance("session:" + SESSION, cursor);
                runs.progress(runId, metrics.llmCalls, metrics.promptTokens, metrics.completionTokens);
                EvalRunner.printProgress(log, cursor, metrics, startedNanos);
                Thread.sleep(TICK_MS);
            }
            System.out.println();
            runs.finish(runId, "done", metrics.llmCalls, metrics.promptTokens, metrics.completionTokens);
        } finally {
            System.out.println("[SMOKE] cleanup: удаление " + slug + " (каскадом с run #" + runId
                    + "), восстановление курсора " + savedCursor);
            try (var st = conn.createStatement()) {
                st.execute("DELETE FROM experiments WHERE slug = '" + slug + "'");
            }
            if (savedCursor > 0) {
                checkpoints.advance("session:" + SESSION, savedCursor);
            } else {
                checkpoints.reset("session:" + SESSION);
            }
        }
    }
}
