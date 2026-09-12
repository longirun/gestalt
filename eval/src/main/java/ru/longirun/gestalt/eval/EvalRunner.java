package ru.longirun.gestalt.eval;

import ru.longirun.gestalt.eval.dedup.DedupPipeline;
import ru.longirun.gestalt.eval.dedup.NearDupMatcher;
import ru.longirun.gestalt.eval.extract.LlmBatchExtractor;
import ru.longirun.gestalt.eval.ingest.Batcher;
import ru.longirun.gestalt.eval.ingest.FixtureSource;
import ru.longirun.gestalt.eval.ingest.HonchoPgSource;
import ru.longirun.gestalt.eval.ingest.LongMemEvalAdapter;
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
import java.util.UUID;

/**
 * CLI eval-каркаса (ADR 28 §3.6): replay → плечи A/B → оракулы → lift → отчёт.
 * Реализованы replay + candidates + wcheck (E1–E2); arms/oracles/report — стадии E3-E5.
 */
public final class EvalRunner {

    public static void main(String[] args) throws Exception {
        String step = args.length > 0 ? args[0] : "all";
        EvalConfig config = EvalConfig.load(EvalPaths.resolve("eval/local.properties"));

        switch (step) {
            case "replay" -> runReplay(config);
            case "candidates" -> runCandidates(config, args);
            case "wcheck" -> runWCheck(config, args);
            case "lme" -> runLmeConvert(config, args);
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
     * Replay живого лога до позиций точек (план 31 §2.9): слепок M — срез строго до
     * триггера (M exclusive: экстракция триггерной реплики возмущает портрет через
     * dedup/reconcile), для L1 дополнительно проба W0 = window_start(M) — слепок после
     * обработки реплик ≤ W0, база W-проверки. Работы (снятие W0-слепка на границе W0,
     * снятие M-слепка на границе «реплика перед M») выполняются по возрастанию границы,
     * а не по M точек: при дырках в логе W0 точки с большим M может лежать до M
     * предыдущей (W0(15723)=15717 < M(15719)=15719) — обход по M проскакивал такие W0.
     * Insufficient grid (M раньше N реплик от начала) — точка откладывается, не reject
     * и не слепок. Инвариант: в gestalt_eval попадают только факты из реплик,
     * обработанных до заморозки слепка.
     */
    private static void runReplay(EvalConfig config) throws Exception {
        if (config.llmApiKey().isBlank()) {
            throw new IllegalStateException("llm.api-key required: replay без LLM бессмыслен (план 31 §2.6)");
        }
        List<EvalPoint> points = EvalDataset.load(EvalPaths.resolve(config.datasetFile()));
        if (config.portraitOwner().isBlank()
                && points.stream().anyMatch(p -> !isLme(config, p.sourceSession()))) {
            throw new IllegalStateException("portrait.owner must be set in local.properties for non-LME points");
        }
        if (config.portraitProject().isBlank()
                && points.stream().anyMatch(p -> !isLme(config, p.sourceSession()))) {
            throw new IllegalStateException("portrait.project must be set in local.properties for non-LME points");
        }
        Map<String, List<EvalPoint>> bySession = groupBySession(points);
        Path snapshotsDir = EvalPaths.evalDir().resolve("out/snapshots");

        int written = 0;
        int w0Written = 0;
        int skipped = 0;
        int postponed = 0;
        Metrics metrics = new Metrics();

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
                String sessionId = "session:" + sourceSession;
                String owner = portraitOwner(config, sourceSession);
                String project = portraitProject(config, sourceSession);
                List<EvalPoint> sessionPoints = entry.getValue().stream()
                        .sorted(java.util.Comparator.comparingLong(EvalPoint::sourceMessageId))
                        .toList();
                List<RawMessage> log = readLog(config, sourceSession);
                if (log.isEmpty()) {
                    throw new IllegalStateException("no messages for session '%s' in %s..%s: check source.db.* and source.day-from/to"
                            .formatted(sourceSession, config.sourceDayFrom(), config.sourceDayTo()));
                }
                long cursor = checkpoints.lastProcessedMessageId(sessionId);
                System.out.printf("[REPLAY] session '%s': %d log messages, checkpoint %d, window N=%d, %d point(s)%n",
                        sourceSession, log.size(), cursor, config.windowSize(), sessionPoints.size());

                // граница = реплика, до которой включительно нужно инжестить до снятия слепка;
                // при равной границе W0-слепок снимается первым (его состояние ⊆ состоянию M-пробы)
                record Work(long bound, int tieBreak, EvalPoint point, boolean w0) {
                }
                List<Work> works = new ArrayList<>();
                for (EvalPoint point : sessionPoints) {
                    boolean needM = !Files.exists(snapshotsDir.resolve(point.id() + ".json"));
                    boolean needW0 = "L1".equals(point.level())
                            && !Files.exists(snapshotsDir.resolve(point.id() + ".w0.json"));
                    if (!needM && !needW0) {
                        skipped++;
                        System.out.printf("[REPLAY] point %s: snapshots exist, skipped%n", point.id());
                        continue;
                    }

                    long m = point.sourceMessageId();
                    int idxM = indexOfMessage(log, m);
                    if (idxM < 0) {
                        throw new IllegalStateException("point %s references message %d outside the log slice"
                                .formatted(point.id(), m));
                    }

                    if (needW0) {
                        long w0 = windowStartId(log, idxM, config.windowSize());
                        if (w0 < 0) {
                            postponed++;
                            System.out.printf("[REPLAY] point %s: POSTPONED insufficient grid (M is among first %d messages)%n",
                                    point.id(), config.windowSize() + 1);
                            continue;
                        }
                        works.add(new Work(w0, 0, point, true));
                    }
                    if (needM) {
                        // граница M-среза — реальная реплика перед M: дырявые id делают m-1 виртуальным,
                        // а соседние точки с одинаковой M не должны валиться дублем (слепок из текущего состояния)
                        long prevId = idxM > 0 ? log.get(idxM - 1).id() : 0;
                        works.add(new Work(prevId, 1, point, false));
                    }
                }
                works.sort(java.util.Comparator.comparingLong(Work::bound)
                        .thenComparingInt(Work::tieBreak));

                for (Work work : works) {
                    if (work.bound() > cursor) {
                        ingest(owner, project, pipeline, extractor, batcher, checkpoints, metrics,
                                log, cursor, work.bound(), sessionId);
                        cursor = work.bound();
                    } else if (work.bound() < cursor) {
                        if (work.w0()) {
                            // теоретически недостижимо (работы по возрастанию границы), но страховка
                            // от рассинхрона чекпоинт/слепки при ручных правках out/snapshots
                            postponed++;
                            System.out.printf("[REPLAY] point %s: POSTPONED W0 %d behind cursor %d%n",
                                    work.point().id(), work.bound(), cursor);
                            continue;
                        }
                        throw new IllegalStateException("snapshot missing for point %s but checkpoint %d is past bound %d: "
                                .formatted(work.point().id(), cursor, work.bound())
                                + "слепок нельзя честно перестроить (в БД факты за границей) — восстановите out/snapshots "
                                + "или сбросьте gestalt_eval и прогоните replay заново");
                    }
                    String file = work.point().id() + (work.w0() ? ".w0.json" : ".json");
                    writeAtomically(snapshotsDir.resolve(file),
                            job.reconcile(owner, project));
                    if (work.w0()) {
                        w0Written++;
                        System.out.printf("[REPLAY] point %s: W0=%d -> snapshot (facts <= W0)%n",
                                work.point().id(), work.bound());
                    } else {
                        written++;
                        System.out.printf("[REPLAY] point %s: M=%d -> snapshot (facts < M, exclusive)%n",
                                work.point().id(), work.point().sourceMessageId());
                    }
                }
            }
        }

        System.out.printf("[REPLAY] done: %d point(s) total, %d M-snapshots, %d W0-snapshots written, "
                        + "%d skipped, %d postponed; LLM extraction calls: %d (prompt %,d, completion %,d tokens)%n",
                points.size(), written, w0Written, skipped, postponed,
                metrics.llmCalls, metrics.promptTokens, metrics.completionTokens);
    }

    /** Обработка реплик (fromId, toId] тем же конвейером, что в спайке: батч → экстракция → дедуп. */
    private static void ingest(String owner, String project, DedupPipeline pipeline, LlmBatchExtractor extractor,
                               Batcher batcher, CheckpointStore checkpoints, Metrics metrics,
                               List<RawMessage> log, long fromId, long toId, String sessionId) throws Exception {
        List<RawMessage> slice = new ArrayList<>();
        for (RawMessage message : log) {
            if (message.id() > fromId && message.id() <= toId) {
                slice.add(message);
            }
        }
        for (List<RawMessage> batch : batcher.batch(slice)) {
            LlmBatchExtractor.ExtractionBatchResult res = extractor.extractWithMetrics(batch);
            metrics.llmCalls++;
            metrics.promptTokens += res.usage().promptTokens();
            metrics.completionTokens += res.usage().completionTokens();
            pipeline.process(owner, sessionId, project, res.facts());
            checkpoints.advance(sessionId, batch.getLast().id());
        }
        checkpoints.advance(sessionId, toId);
    }

    /**
     * W0 = window_start(M) = последняя реплика ВНЕ окна (§2.9): окно плеч — (W0, M),
     * N реплик. Инвариант «max evidence-id ≤ W0» строго эквивалентен «происхождение
     * факта вне окна»: реплика W0 видимой плечам не является, поэтому рождение из неё
     * — честная работа памяти, а не подсказка из промпта (критика off-by-one, правка
     * 2026-09-11: прежнее W0 = первая реплика окна размывало lift — плечо B читало
     * факт-покрытие прямо из окна, дельта A−B вырождалась в ничью).
     */
    static long windowStartId(List<RawMessage> log, int idxM, int windowSize) {
        return idxM > windowSize ? log.get(idxM - windowSize - 1).id() : -1;
    }

    private static int indexOfMessage(List<RawMessage> log, long id) {
        for (int i = 0; i < log.size(); i++) {
            if (log.get(i).id() == id) {
                return i;
            }
        }
        return -1;
    }

    private static final class Metrics {
        long llmCalls;
        long promptTokens;
        long completionTokens;
    }

    /**
     * W-проверка L1-точек (план 31 §2.9): слепки W0 и M + evidence из gestalt_eval →
     * валидные факты-кандидаты покрытия (max evidence ≤ W0), in-window, encoding-lag.
     * Отчёт перезаписывается в out/wcheck.jsonl (viewer и E5 читают его).
     */
    private static void runWCheck(EvalConfig config, String[] args) throws Exception {
        String only = args.length > 1 ? args[1] : "";
        List<EvalPoint> points = EvalDataset.load(EvalPaths.resolve(config.datasetFile()));
        Path snapshotsDir = EvalPaths.evalDir().resolve("out/snapshots");
        Path outFile = EvalPaths.evalDir().resolve("out/wcheck.jsonl");
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        Map<String, Map<UUID, List<Long>>> evidenceByProject = new LinkedHashMap<>();
        try (Connection conn = DriverManager.getConnection(
                config.targetDbUrl(), config.targetDbUser(), config.targetDbPassword())) {
            if (config.portraitOwner().isBlank()
                    && points.stream().anyMatch(p -> !isLme(config, p.sourceSession()))) {
                throw new IllegalStateException("portrait.owner must be set in local.properties for non-LME points");
            }
            FactRepository repo = new FactRepository(conn);
            Map<String, String> ownerByProject = new LinkedHashMap<>();
            for (EvalPoint p : points) {
                String project = portraitProject(config, p.sourceSession());
                String owner = portraitOwner(config, p.sourceSession());
                ownerByProject.merge(project, owner,
                        (a, b) -> a.equals(b) ? a : throwMixedOwner(project, a, b));
            }
            if (ownerByProject.containsKey("")) {
                throw new IllegalStateException("portrait.project must be set in local.properties for non-LME points");
            }
            for (Map.Entry<String, String> e : ownerByProject.entrySet()) {
                Map<UUID, List<Long>> evidence = new LinkedHashMap<>();
                for (FactRepository.StoredFact fact : repo.findByOwnerAndProject(e.getValue(), e.getKey())) {
                    evidence.put(fact.id(), fact.evidenceMessageIds());
                }
                evidenceByProject.put(e.getKey(), evidence);
            }
        }

        StringBuilder out = new StringBuilder();
        int checked = 0;
        int answerCovered = 0;
        int insufficient = 0;
        for (EvalPoint point : points) {
            if (!only.isEmpty() && !point.id().equals(only)) {
                continue;
            }
            if (!"L1".equals(point.level())) {
                continue;
            }
            Path w0File = snapshotsDir.resolve(point.id() + ".w0.json");
            Path mFile = snapshotsDir.resolve(point.id() + ".json");
            if (!Files.exists(w0File) || !Files.exists(mFile)) {
                insufficient++;
                out.append(mapper.writeValueAsString(Map.of("pointId", point.id(), "status", "insufficient-grid")))
                        .append('\n');
                continue;
            }
            List<RawMessage> log = readLog(config, point.sourceSession());
            int idxM = indexOfMessage(log, point.sourceMessageId());
            long w0 = idxM >= 0 ? windowStartId(log, idxM, config.windowSize()) : -1;
            if (w0 < 0) {
                insufficient++;
                out.append(mapper.writeValueAsString(Map.of("pointId", point.id(), "status", "insufficient-grid")))
                        .append('\n');
                continue;
            }
            Map<UUID, List<Long>> evidence =
                    evidenceByProject.get(portraitProject(config, point.sourceSession()));
            WCheck.PointReport report = WCheck.check(point.id(), point.must(),
                    Files.readString(mFile), w0, point.sourceMessageId(),
                    factId -> evidence == null ? List.of() : evidence.getOrDefault(factId, List.of()));
            com.fasterxml.jackson.databind.node.ObjectNode node = mapper.valueToTree(report);
            node.put("status", report.covered() ? "covered" : "not-covered");
            out.append(mapper.writeValueAsString(node)).append('\n');
            checked++;
            if (report.answerCovered()) {
                answerCovered++;
            }
            System.out.printf("[WCHECK] point %s: %s (answer %s, valid %d, in-window %d, lag %d, unknown %d)%n",
                    point.id(), report.covered() ? "covered" : "not-covered",
                    report.answerCovered() ? "covered" : "not-covered",
                    report.valid().size(), report.inWindow().size(), report.lag().size(),
                    report.unknown().size());
        }

        Files.createDirectories(outFile.getParent());
        Files.writeString(outFile, out.toString());
        System.out.printf("[WCHECK] done: %d checked, %d answer-covered, %d insufficient-grid -> %s%n",
                checked, answerCovered, insufficient, outFile.normalize());
    }

    /**
     * Конвертация LongMemEval → dataset/points.lme.jsonl (positive control каркаса, план 31):
     * `run -Pargs='lme [types-csv] [limit]'`, дефолт single-session-user × 30; файл — source.lme.file.
     * C-точек в релизе LongMemEval нет (категория abstention отсутствует) — контроль молчания
     * остаётся за живым датасетом; портреты вопросов изолированы (project = sourceSession).
     */
    private static void runLmeConvert(EvalConfig config, String[] args) throws Exception {
        String typesCsv = args.length > 1 && !args[1].isBlank() ? args[1] : "single-session-user";
        int limit = args.length > 2 ? Integer.parseInt(args[2]) : 30;
        if (config.sourceLmeFile().isBlank()) {
            throw new IllegalStateException("source.lme.file required in local.properties");
        }
        LongMemEvalAdapter adapter = new LongMemEvalAdapter(EvalPaths.resolve(config.sourceLmeFile()));
        List<EvalPoint> points = LmePoints.convert(adapter, java.util.Set.of(typesCsv.split(",")), limit, config.windowSize());
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        StringBuilder sb = new StringBuilder();
        for (EvalPoint point : points) {
            sb.append(mapper.writeValueAsString(point)).append('\n');
        }
        Path out = EvalPaths.evalDir().resolve("dataset/points.lme.jsonl");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString());
        System.out.printf("[LME] %d point(s) (types=%s, limit=%d) -> %s%n",
                points.size(), typesCsv, limit, out.normalize());
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
        if (isLme(config, sourceSession)) {
            String questionId = sourceSession.substring(LongMemEvalAdapter.SESSION_PREFIX.length());
            return new LongMemEvalAdapter(EvalPaths.resolve(config.sourceLmeFile())).messages(questionId);
        }
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

    /** LongMemEval-режим: сессия точки имеет вид lme-<question_id> и задан source.lme.file. */
    static boolean isLme(EvalConfig config, String sourceSession) {
        return sourceSession != null
                && sourceSession.startsWith(LongMemEvalAdapter.SESSION_PREFIX)
                && !config.sourceLmeFile().isBlank();
    }

    /** Портрет-проект точки: LME-вопрос — изолированный микромир (project = sourceSession), иначе конфиг. */
    static String portraitProject(EvalConfig config, String sourceSession) {
        return isLme(config, sourceSession) ? sourceSession : config.portraitProject();
    }

    /**
     * Портрет-владелец точки: LME-вопрос — синтетический владелец (user:lme-<qid>). USER-scope
     * факты шарятся между проектами одного владельца (Р19) — живые USER-факты owner из конфига
     * иначе протекают в слепки positive control'а (E2-PC). Владелец ≠ из конфига => изоляция.
     */
    static String portraitOwner(EvalConfig config, String sourceSession) {
        return isLme(config, sourceSession) ? "user:" + sourceSession : config.portraitOwner();
    }

    /** Один проект — один владелец: смешение означало бы общую evidence-карту между несвязанными точками. */
    private static String throwMixedOwner(String project, String a, String b) {
        throw new IllegalStateException("project %s is used by different owners: %s vs %s"
                .formatted(project, a, b));
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
