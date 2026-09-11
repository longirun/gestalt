package ru.longirun.gestalt.eval;

import ru.longirun.gestalt.eval.dedup.DedupPipeline;
import ru.longirun.gestalt.eval.dedup.DedupPipeline.DedupStats;
import ru.longirun.gestalt.eval.dedup.NearDupMatcher;
import ru.longirun.gestalt.eval.extract.LlmBatchExtractor;
import ru.longirun.gestalt.eval.ingest.Batcher;
import ru.longirun.gestalt.eval.ingest.FixtureSource;
import ru.longirun.gestalt.eval.ingest.HonchoPgSource;
import ru.longirun.gestalt.eval.ingest.MessageSource;
import ru.longirun.gestalt.eval.ingest.RawMessage;
import ru.longirun.gestalt.eval.llm.LlmClient;
import ru.longirun.gestalt.eval.portrait.ReconciliationJob;
import ru.longirun.gestalt.eval.portrait.SnapshotBuilder;
import ru.longirun.gestalt.eval.portrait.SnapshotStore;
import ru.longirun.gestalt.eval.store.CheckpointStore;
import ru.longirun.gestalt.eval.store.FactRepository;
import ru.longirun.gestalt.eval.store.SchemaMigrator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CLI eval-каркаса (ADR 28 §3.6): replay → плечи A/B → оракулы → lift → отчёт.
 * Реализован replay (E1); arms/oracles/report — стадии E3-E5.
 */
public final class EvalRunner {

    public static void main(String[] args) throws Exception {
        String step = args.length > 0 ? args[0] : "all";
        EvalConfig config = EvalConfig.load(EvalPaths.resolve("eval/local.properties"));

        switch (step) {
            case "replay" -> runReplay(config);
            case "candidates" -> runCandidates(config, args);
            case "arms" -> runArms(config);
            case "oracles" -> runOracles(config);
            case "report" -> runReport(config);
            case "all" -> runAll(config);
            default -> throw new IllegalArgumentException("unknown step: " + step);
        }
    }

    /**
     * Выгрузка реплик дня в eval/data/candidates.jsonl для ручного просмотра и viewer'а
     * (план 31 §E2): `run -Pargs='candidates <session>'`; день — source.day конфига.
     * Перезаписывает файл: день статичен (прошлое лога), выгрузка детерминирована.
     */
    private static void runCandidates(EvalConfig config, String[] args) throws Exception {
        if (args.length < 2 || args[1].isBlank()) {
            throw new IllegalArgumentException("usage: candidates <sourceSession>");
        }
        String sourceSession = args[1];
        List<RawMessage> log = readLog(config, sourceSession);
        if (log.isEmpty()) {
            throw new IllegalStateException("no messages for session '%s' in %s..%s: check source.db.* and source.day-from/to"
                    .formatted(sourceSession, config.sourceDayFrom(), config.sourceDayTo()));
        }
        Path out = EvalPaths.evalDir().resolve("data/candidates.jsonl");
        Files.createDirectories(out.getParent());
        StringBuilder sb = new StringBuilder();
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (RawMessage m : log) {
            com.fasterxml.jackson.databind.node.ObjectNode node = mapper.createObjectNode();
            node.put("id", m.id());
            node.put("session", m.sessionName());
            node.put("peer", m.peerName());
            node.put("tokens", m.tokenCount());
            node.put("time", m.createdAt() == null ? null : m.createdAt().toString());
            node.put("content", m.content());
            sb.append(mapper.writeValueAsString(node)).append('\n');
        }
        Files.writeString(out, sb.toString());
        System.out.printf("[CANDIDATES] session '%s', %s..%s: %d message(s) -> %s%n",
                sourceSession, config.sourceDayFrom(), config.sourceDayTo(), log.size(), out.normalize());
    }

    /**
     * Replay живого лога до позиции T каждой точки (план 31 §E1): один последовательный
     * проход (батч → экстракция → дедуп → факты в gestalt_eval), при достижении T —
     * reconcile и материализация слепка в out/snapshots/<pointId>.json.
     * Инвариант: в gestalt_eval попадают только факты из реплик ≤ текущей T —
     * реплики за T точки не обрабатываются, пока её слепок не заморожен на диске.
     */
    private static void runReplay(EvalConfig config) throws Exception {
        if (config.llmApiKey().isBlank()) {
            throw new IllegalStateException("llm.api-key required: replay без LLM бессмыслен (план 31 §2.6)");
        }
        if (config.portraitOwner().isBlank() || config.portraitProject().isBlank()) {
            throw new IllegalStateException("portrait.owner and portrait.project must be set in local.properties");
        }

        List<EvalPoint> points = EvalDataset.load(EvalPaths.resolve(config.datasetFile()));
        Map<String, List<EvalPoint>> bySession = groupBySession(points);
        Path snapshotsDir = EvalPaths.evalDir().resolve("out/snapshots");

        int written = 0;
        int skipped = 0;
        long llmCalls = 0;
        long promptTokens = 0;
        long completionTokens = 0;

        try (Connection conn = DriverManager.getConnection(
                config.targetDbUrl(), config.targetDbUser(), config.targetDbPassword())) {
            SchemaMigrator.migrate(conn);
            FactRepository repo = new FactRepository(conn);
            DedupPipeline pipeline = new DedupPipeline(repo, new NearDupMatcher());
            CheckpointStore checkpoints = new CheckpointStore(conn);
            ReconciliationJob job = new ReconciliationJob(repo, new SnapshotBuilder(), new SnapshotStore(conn));
            LlmBatchExtractor extractor = new LlmBatchExtractor(new LlmClient(
                    config.llmBaseUrl(), config.llmApiKey(), config.llmModel(), config.llmReasoningEffort()));
            Batcher batcher = new Batcher(config.batchMaxMessages(), config.batchMaxTokens());

            for (Map.Entry<String, List<EvalPoint>> entry : bySession.entrySet()) {
                String sourceSession = entry.getKey();
                // Допущение E1: односессионный срез; сессии обрабатываются в порядке датасета,
                // межсессионная хронология — по факту датасета E2.
                String sessionId = "session:" + sourceSession;
                List<EvalPoint> sessionPoints = entry.getValue();
                List<RawMessage> log = readLog(config, sourceSession);
                long logMaxId = log.isEmpty() ? -1 : log.getLast().id();
                long cursor = checkpoints.lastProcessedMessageId(sessionId);
                System.out.printf("[REPLAY] session '%s': %d log messages (max id %d), checkpoint %d, %d point(s)%n",
                        sourceSession, log.size(), logMaxId, cursor, sessionPoints.size());

                for (EvalPoint point : sessionPoints) {
                    Path snapshotFile = snapshotsDir.resolve(point.id() + ".json");
                    if (Files.exists(snapshotFile)) {
                        skipped++;
                        System.out.printf("[REPLAY] point %s: snapshot exists, skipped%n", point.id());
                        continue;
                    }
                    long t = point.sourceMessageId();
                    if (t < cursor) {
                        throw new IllegalStateException("snapshot missing for point %s but checkpoint %d is beyond T %d: "
                                .formatted(point.id(), cursor, t)
                                + "слепок нельзя честно перестроить (в БД факты за T) — восстановите out/snapshots "
                                + "или сбросьте gestalt_eval и прогоните replay заново");
                    }
                    if (t > logMaxId) {
                        throw new IllegalStateException("point %s references message %d beyond log end (max id %d)"
                                .formatted(point.id(), t, logMaxId));
                    }

                    List<RawMessage> slice = new ArrayList<>();
                    for (RawMessage message : log) {
                        if (message.id() > cursor && message.id() <= t) {
                            slice.add(message);
                        }
                    }

                    int factsTotal = 0;
                    int batches = 0;
                    for (List<RawMessage> batch : batcher.batch(slice)) {
                        LlmBatchExtractor.ExtractionBatchResult res = extractor.extractWithMetrics(batch);
                        llmCalls++;
                        promptTokens += res.usage().promptTokens();
                        completionTokens += res.usage().completionTokens();
                        DedupStats stats = pipeline.process(
                                config.portraitOwner(), sessionId, config.portraitProject(), res.facts());
                        factsTotal += stats.total();
                        batches++;
                        checkpoints.advance(sessionId, batch.getLast().id());
                    }
                    checkpoints.advance(sessionId, t);

                    String snapshotJson = job.reconcile(config.portraitOwner(), config.portraitProject());
                    writeAtomically(snapshotFile, snapshotJson);
                    written++;
                    cursor = t;
                    System.out.printf("[REPLAY] point %s: T=%d (+%d msgs, %d batches, %d facts) -> %s%n",
                            point.id(), t, slice.size(), batches, factsTotal,
                            snapshotFile.normalize());
                }
            }
        }

        System.out.printf("[REPLAY] done: %d point(s) total, %d written, %d skipped; "
                        + "LLM extraction calls: %d (prompt %,d, completion %,d tokens)%n",
                points.size(), written, skipped, llmCalls, promptTokens, completionTokens);
    }

    /** Оба плеча на триггерах: A — с инъекцией PortraitSnapshot, B — контроль без портрета (заморозка кэша). */
    private static void runArms(EvalConfig config) {
        throw new UnsupportedOperationException("not implemented yet: arms");
    }

    /** Машинные проверки ответов: must/must_not, edit-rate; C-точки — отдельный булев leak-гейт. */
    private static void runOracles(EvalConfig config) {
        throw new UnsupportedOperationException("not implemented yet: oracles");
    }

    /** Lift на решённых парах + McNemar exact (α = 0.05), конструируемость; вердикты асимметричны (§4). */
    private static void runReport(EvalConfig config) {
        throw new UnsupportedOperationException("not implemented yet: report");
    }

    private static void runAll(EvalConfig config) throws Exception {
        runReplay(config);
        System.out.println("[EVAL] arms/oracles/report: not implemented yet (stages E3-E5)");
    }

    private static Map<String, List<EvalPoint>> groupBySession(List<EvalPoint> points) {
        Map<String, List<EvalPoint>> bySession = new LinkedHashMap<>();
        for (EvalPoint point : points) {
            bySession.computeIfAbsent(point.sourceSession(), s -> new ArrayList<>()).add(point);
        }
        bySession.values().forEach(list ->
                list.sort(java.util.Comparator.comparingLong(EvalPoint::sourceMessageId)));
        return bySession;
    }

    private static List<RawMessage> readLog(EvalConfig config, String sourceSession) throws Exception {
        if (!config.sourceFixture().isBlank()) {
            System.out.printf("[REPLAY] Reading fixture %s...%n", config.sourceFixture());
            MessageSource source = new FixtureSource(EvalPaths.resolve(config.sourceFixture()));
            return source.read();
        }
        if (config.sourceDbUrl().isBlank() || config.sourceDayFrom().isBlank()) {
            throw new IllegalStateException("source.fixture or (source.db.url + source.day-from[/to]) required");
        }
        return new HonchoPgSource(
                config.sourceDbUrl(),
                config.sourceDbUser(),
                config.sourceDbPassword(),
                sourceSession,
                config.sourceDayFrom(),
                config.sourceDayTo().isBlank() ? config.sourceDayFrom() : config.sourceDayTo()).read();
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
