package ru.longirun.gestalt.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
import ru.longirun.gestalt.eval.store.ExperimentStore;
import ru.longirun.gestalt.eval.store.FactRepository;
import ru.longirun.gestalt.eval.store.PointSnapshotStore;
import ru.longirun.gestalt.eval.store.ResultStore;
import ru.longirun.gestalt.eval.store.RunStore;
import ru.longirun.gestalt.eval.store.SchemaMigrator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
            case "replay" -> runReplay(config, args);
            case "candidates" -> runCandidates(config, args);
            case "validate" -> runValidate(config, args);
            case "wcheck" -> runWCheck(config, args);
            case "lme" -> runLmeConvert(config, args);
            case "arms" -> runArms(config, args);
            case "oracles" -> runOracles(config, args);
            case "report" -> runReport(config, args);
            case "runs" -> runRuns(config, args);
            case "delrun" -> runDelRun(config, args);
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
        ObjectMapper mapper = new ObjectMapper();
        for (RawMessage m : log) {
            ObjectNode node = mapper.createObjectNode();
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
     * Аудит датасета спан-инвариантами И1–И3 (спека 33 §4): 0 LLM, 0 PG — валидатор
     * дешевле любой стадии прибора, мусор отсекается до replay. `validate [datasetPath]`:
     * без аргумента — dataset.file конфига, с аргументом — путь к jsonl (ретро-прогон
     * архивов без правки properties). Журнал разметки — out/validate.<имя-датасета>
     * (перезапись: прогон детерминирован); reject-статусы — не исключение, validate
     * всегда завершается полным отчётом.
     */
    private static void runValidate(EvalConfig config, String[] args) throws Exception {
        String dataset = args.length > 1 && !args[1].isBlank() ? args[1] : config.datasetFile();
        List<EvalPoint> points = EvalDataset.load(EvalPaths.resolve(dataset));
        ObjectMapper mapper = new ObjectMapper();

        Map<String, Integer> counts = new LinkedHashMap<>();
        StringBuilder journal = new StringBuilder();
        for (Map.Entry<String, List<EvalPoint>> entry : groupBySession(points).entrySet()) {
            String sourceSession = entry.getKey();
            boolean lme = isLme(config, sourceSession);
            List<RawMessage> log = lme ? List.of() : readLog(config, sourceSession);
            if (!lme && log.isEmpty()) {
                throw new IllegalStateException("no messages for session '%s' in %s..%s: check source.db.* and source.day-from/to"
                        .formatted(sourceSession, config.sourceDayFrom(), config.sourceDayTo()));
            }
            for (EvalPoint point : entry.getValue()) {
                SpanValidator.PointVerdict verdict =
                        SpanValidator.validate(point, log, config.windowSize(), lme);
                counts.merge(verdict.status(), 1, Integer::sum);
                System.out.printf("[VALIDATE] point %s: %s%s%n", point.id(), verdict.status(),
                        verdict.details().isEmpty() ? "" : " — " + String.join("; ", verdict.details()));
                journal.append(mapper.writeValueAsString(verdict)).append('\n');
            }
        }

        Path out = EvalPaths.evalDir().resolve("out/validate." + Path.of(dataset).getFileName());
        Files.createDirectories(out.getParent());
        Files.writeString(out, journal.toString());
        StringBuilder summary = new StringBuilder();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (!summary.isEmpty()) {
                summary.append(", ");
            }
            summary.append(e.getKey()).append(' ').append(e.getValue());
        }
        System.out.printf("[VALIDATE] %s: %d point(s) -> %s | %s%n",
                dataset, points.size(), out.normalize(), summary);
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
     * Writer-проход §7: слепки каноничны в snapshots (PG), out/snapshots — дамп;
     * прогон оборачивается в run (экономика, статусы, резюм = новый run).
     */
    private static void runReplay(EvalConfig config, String[] args) throws Exception {
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
        String slugArg = args.length > 1 && !args[1].isBlank() ? args[1] : null;

        int written = 0;
        int w0Written = 0;
        int skipped = 0;
        int postponed = 0;
        Metrics metrics = new Metrics();

        try (Connection conn = DriverManager.getConnection(
                config.targetDbUrl(), config.targetDbUser(), config.targetDbPassword())) {
            SchemaMigrator.migrate(conn);
            String experiment = Experiments.forReplay(conn, config, points, slugArg);
            RunStore runs = new RunStore(conn);
            int stale = runs.interruptStale(experiment, "replay");
            if (stale > 0) {
                System.out.printf("[REPLAY] %d застрявших running-прогонов replay помечены interrupted (§7.3)%n", stale);
            }
            long runId = runs.start(experiment, "replay", "слепки → snapshots (PG) + дамп out/");
            PointSnapshotStore snapshotStore = new PointSnapshotStore(conn);
            try {
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
                            .sorted(Comparator.comparingLong(EvalPoint::sourceMessageId))
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
                        boolean needM = !snapshotStore.exists(experiment, point.id(), "m");
                        boolean needW0 = "L1".equals(point.level())
                                && !snapshotStore.exists(experiment, point.id(), "w0");
                        if (!needM && !needW0) {
                            skipped++;
                            System.out.printf("[REPLAY] point %s: snapshots exist (PG), skipped%n", point.id());
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
                    works.sort(Comparator.comparingLong(Work::bound)
                            .thenComparingInt(Work::tieBreak));

                    for (Work work : works) {
                        if (work.bound() > cursor) {
                            ingest(owner, project, pipeline, extractor, batcher, checkpoints, metrics,
                                    log, cursor, work.bound(), sessionId, repo);
                            cursor = work.bound();
                        } else if (work.bound() < cursor) {
                            if (work.w0()) {
                                // теоретически недостижимо (работы по возрастанию границы), но страховка
                                // от рассинхрона чекпоинт/слепки при ручных правках snapshots
                                postponed++;
                                System.out.printf("[REPLAY] point %s: POSTPONED W0 %d behind cursor %d%n",
                                        work.point().id(), work.bound(), cursor);
                                continue;
                            }
                            throw new IllegalStateException("snapshot missing for point %s but checkpoint %d is past bound %d: "
                                    .formatted(work.point().id(), cursor, work.bound())
                                    + "слепок нельзя честно перестроить (в БД факты за границей) — восстановите snapshots "
                                    + "или сбросьте gestalt_eval и прогоните replay заново");
                        }
                        String json = job.reconcile(owner, project);
                        String kind = work.w0() ? "w0" : "m";
                        snapshotStore.upsert(experiment, work.point().id(), kind, json, runId);
                        writeAtomically(snapshotsDir.resolve(work.point().id()
                                + ("w0".equals(kind) ? ".w0.json" : ".json")), json);
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
                runs.finish(runId, "done", metrics.llmCalls, metrics.promptTokens, metrics.completionTokens);
            } catch (Exception e) {
                runs.finish(runId, "failed", metrics.llmCalls, metrics.promptTokens, metrics.completionTokens);
                throw e;
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
                               List<RawMessage> log, long fromId, long toId, String sessionId,
                               FactRepository repo) throws Exception {
        List<RawMessage> slice = new ArrayList<>();
        for (RawMessage message : log) {
            if (message.id() > fromId && message.id() <= toId) {
                slice.add(message);
            }
        }
        for (List<RawMessage> batch : batcher.batch(slice)) {
            List<String> knownPredicates = repo.findKnownPredicates(project, 200);
            LlmBatchExtractor.ExtractionBatchResult res = extractor.extractWithMetrics(batch, knownPredicates);
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

    static int indexOfMessage(List<RawMessage> log, long id) {
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
     * Writer-проход §7: слепки читает из snapshots (PG), результат пишет в wchecks (PG).
     * Прогон оборачивается в run (история попыток).
     */
    private static void runWCheck(EvalConfig config, String[] args) throws Exception {
        String only = args.length > 1 ? args[1] : "";
        List<EvalPoint> points = EvalDataset.load(EvalPaths.resolve(config.datasetFile()));
        ObjectMapper mapper = new ObjectMapper();

        int checked = 0;
        int answerCovered = 0;
        int insufficient = 0;
        try (Connection conn = DriverManager.getConnection(
                config.targetDbUrl(), config.targetDbUser(), config.targetDbPassword())) {
            SchemaMigrator.migrate(conn);
            ExperimentStore experiments = new ExperimentStore(conn);
            String experiment = Experiments.resolveActive(experiments, null);
            RunStore runs = new RunStore(conn);
            PointSnapshotStore snapshots = new PointSnapshotStore(conn);
            ResultStore results = new ResultStore(conn);

            if (config.portraitOwner().isBlank()
                    && points.stream().anyMatch(p -> !isLme(config, p.sourceSession()))) {
                throw new IllegalStateException("portrait.owner must be set in local.properties for non-LME points");
            }
            int stale = runs.interruptStale(experiment, "wcheck");
            if (stale > 0) {
                System.out.printf("[WCHECK] %d застрявших running-прогонов wcheck помечены interrupted (§7.3)%n", stale);
            }
            long runId = runs.start(experiment, "wcheck", "W-проверка: слепки PG → wchecks PG");
            try {
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
                Map<String, Map<UUID, List<Long>>> evidenceByProject = new LinkedHashMap<>();
                for (Map.Entry<String, String> e : ownerByProject.entrySet()) {
                    Map<UUID, List<Long>> evidence = new LinkedHashMap<>();
                    for (FactRepository.StoredFact fact : repo.findByOwnerAndProject(e.getValue(), e.getKey())) {
                        evidence.put(fact.id(), fact.evidenceMessageIds());
                    }
                    evidenceByProject.put(e.getKey(), evidence);
                }

                Map<String, List<RawMessage>> logBySession = new LinkedHashMap<>();

                for (EvalPoint point : points) {
                    if (!only.isEmpty() && !point.id().equals(only)) {
                        continue;
                    }
                    if (!"L1".equals(point.level())) {
                        continue;
                    }
                    String mJson = snapshots.find(experiment, point.id(), "m").orElse(null);
                    String w0Json = snapshots.find(experiment, point.id(), "w0").orElse(null);
                    if (mJson == null || w0Json == null) {
                        insufficient++;
                        recordInsufficient(results, experiment, point.id(), mapper, "insufficient-grid");
                        continue;
                    }
                    List<RawMessage> log = sessionLog(logBySession, config, point.sourceSession());
                    int idxM = indexOfMessage(log, point.sourceMessageId());
                    long w0 = idxM >= 0 ? windowStartId(log, idxM, config.windowSize()) : -1;
                    if (w0 < 0) {
                        insufficient++;
                        recordInsufficient(results, experiment, point.id(), mapper, "insufficient-grid");
                        continue;
                    }
                    Map<UUID, List<Long>> evidence =
                            evidenceByProject.get(portraitProject(config, point.sourceSession()));
                    WCheck.PointReport report = WCheck.check(point.id(), point.must(),
                            mJson, w0, point.sourceMessageId(),
                            factId -> evidence.getOrDefault(factId, List.of()));
                    ObjectNode node = mapper.valueToTree(report);
                    node.put("status", report.covered() ? "covered" : "not-covered");
                    String payload = mapper.writeValueAsString(node);
                    results.upsertWCheck(experiment, point.id(), report.covered(), report.answerCovered(), payload);
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

                runs.finish(runId, "done");
            } catch (Exception e) {
                runs.finish(runId, "failed");
                throw e;
            }
        }

        System.out.printf("[WCHECK] done: %d checked, %d answer-covered, %d insufficient-grid%n",
                checked, answerCovered, insufficient);
    }

    private static void recordInsufficient(ResultStore results, String experiment, String pointId,
                                           ObjectMapper mapper,
                                           String status) throws Exception {
        String payload = mapper.writeValueAsString(Map.of("pointId", pointId, "status", status));
        results.upsertWCheck(experiment, pointId, null, null, payload);
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
        List<EvalPoint> points = LmePoints.convert(adapter, Set.of(typesCsv.split(",")), limit, config.windowSize());
        ObjectMapper mapper = new ObjectMapper();
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
    private static void runArms(EvalConfig config, String[] args) throws Exception {
        int limit = args.length > 1 && !args[1].isBlank() ? Integer.parseInt(args[1]) : 0;
        Arms.run(config, limit);
    }

    /** Машинные проверки ответов: must/must_not (границы слов, без судей — ярус smoke); C-точки — булев leak-гейт. */
    private static void runOracles(EvalConfig config, String[] args) throws Exception {
        Oracles.run(config, args.length > 1 && !args[1].isBlank() ? args[1] : null);
    }

    /**
     * История прогонов эксперимента (§7.3/§7.4): `runs [slug]` — экономика каждого
     * прогона (llm_calls/токены) и статусы; def — единственный active.
     */
    private static void runRuns(EvalConfig config, String[] args) throws Exception {
        try (Connection conn = DriverManager.getConnection(
                config.targetDbUrl(), config.targetDbUser(), config.targetDbPassword())) {
            SchemaMigrator.migrate(conn);
            String experiment = Experiments.resolveActive(new ExperimentStore(conn),
                    args.length > 1 && !args[1].isBlank() ? args[1] : null);
            System.out.printf("[RUNS] experiment %s:%n", experiment);
            System.out.printf("  %-4s %-9s %-11s %-9s %-14s %-11s%n",
                    "#", "stage", "status", "llm", "tokens p/c", "finished");
            for (RunStore.RunRow run : new RunStore(conn).list(experiment)) {
                String llm = run.llmCalls() == null ? "—" : String.valueOf(run.llmCalls());
                String tokens = run.promptTokens() == null || run.completionTokens() == null
                        ? "—" : run.promptTokens() + "/" + run.completionTokens();
                String finished = run.finishedAt() == null ? "—" : run.finishedAt().toLocalDate().toString();
                System.out.printf("  %-4d %-9s %-11s %-9s %-14s %-11s %s%n",
                        run.id(), run.stage(), run.status(), llm, tokens, finished,
                        run.note() == null ? "" : run.note());
            }
        }
    }

    /** Удаление прогона (§7.3 — штатная операция): `delrun <id>` — run + неперезаписанные артефакты каскадом. */
    private static void runDelRun(EvalConfig config, String[] args) throws Exception {
        if (args.length < 2 || args[1].isBlank()) {
            throw new IllegalArgumentException("usage: delrun <runId>");
        }
        long runId = Long.parseLong(args[1]);
        try (Connection conn = DriverManager.getConnection(
                config.targetDbUrl(), config.targetDbUser(), config.targetDbPassword())) {
            SchemaMigrator.migrate(conn);
            RunStore.RunDeletion deleted = new RunStore(conn).delete(runId);
            System.out.printf("[DELRUN] run #%d (%s/%s, %s) удалён: answers %d, snapshots %d "
                            + "(перезаписанные позже — выжили, run_id чужой)%n",
                    deleted.id(), deleted.experiment(), deleted.stage(), deleted.status(),
                    deleted.deletedAnswers(), deleted.deletedSnapshots());
        }
    }

    /** Lift на решённых парах + McNemar exact (α = 0.05), конструируемость; вердикты асимметричны (§4).
     *  Аргумент — слаг эксперимента (не задан → единственный active); вердикты читает из PG (§7). */
    private static void runReport(EvalConfig config, String[] args) throws Exception {
        Report.run(config, args.length > 1 && !args[1].isBlank() ? args[1] : null);
    }

    private static void runAll(EvalConfig config) throws Exception {
        runReplay(config, new String[]{"replay"});
        Arms.run(config, 0);
        Oracles.run(config, null);
        Report.run(config, null);
    }

    private static Map<String, List<EvalPoint>> groupBySession(List<EvalPoint> points) {
        Map<String, List<EvalPoint>> bySession = new LinkedHashMap<>();
        for (EvalPoint point : points) {
            bySession.computeIfAbsent(point.sourceSession(), s -> new ArrayList<>()).add(point);
        }
        bySession.values().forEach(list ->
                list.sort(Comparator.comparingLong(EvalPoint::sourceMessageId)));
        return bySession;
    }

    /** Чтение лога точки (LME-микромир или live-срез) — общий для replay/wcheck/arms. */
    static List<RawMessage> readLog(EvalConfig config, String sourceSession) throws Exception {
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

    /**
     * Лог сессии с кэшем прогона: живой срез = коннект к source-PG — читаем один раз
     * на сессию, а не на точку (как в replay, но лениво: из-за фильтров only/limit
     * в wcheck/arms нельзя грузить все сессии заранее).
     */
    static List<RawMessage> sessionLog(Map<String, List<RawMessage>> cache,
                                       EvalConfig config, String sourceSession) throws Exception {
        List<RawMessage> log = cache.get(sourceSession);
        if (log == null) {
            log = readLog(config, sourceSession);
            cache.put(sourceSession, log);
        }
        return log;
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
