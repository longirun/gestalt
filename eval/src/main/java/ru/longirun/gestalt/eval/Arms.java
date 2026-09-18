package ru.longirun.gestalt.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.longirun.gestalt.eval.ingest.RawMessage;
import ru.longirun.gestalt.eval.llm.LlmClient;
import ru.longirun.gestalt.eval.store.ExperimentStore;
import ru.longirun.gestalt.eval.store.PointSnapshotStore;
import ru.longirun.gestalt.eval.store.ResultStore;
import ru.longirun.gestalt.eval.store.RunStore;
import ru.longirun.gestalt.eval.store.SchemaMigrator;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Стадия E3 — плечи A/B (ADR 28 §3.3): оба плеча отвечают на триггер M с идентичным
 * видимым контекстом — окном (W0, M) + вопросом; плечо A дополнительно получает дайджест
 * PortraitSnapshot в системном промпте, B — контроль без памяти. Одинаковая модель
 * (llm.answer.* — резолвнутые значения, см. EvalConfig.load), temperature 0. Канон ответов — gestalt_eval.answers
 * (§7 writer: {answer, model, tokens, latencyMs, at, promptTokens, completionTokens});
 * существующие пары в PG не перегенерируются — кэш плеч замораживается (ADR 28),
 * пока answer_fp не сменился.
 */
public final class Arms {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Arms() {
    }

    /** Дайджест слепка для инъекции плечу A: секции critical/constructs/preferences → строки-стейтменты. */
    static String memoryDigest(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try {
            JsonNode root = MAPPER.readTree(snapshotJson);
            for (String section : List.of("critical", "constructs", "preferences")) {
                JsonNode facts = root.path(section);
                if (!facts.isArray() || facts.isEmpty()) {
                    continue;
                }
                sb.append("[").append(section).append("]\n");
                for (JsonNode fact : facts) {
                    String statement = fact.path("statement").asText("");
                    String line = !statement.isBlank()
                            ? statement
                            : spo(fact.path("subject").asText(""),
                                  fact.path("predicate").asText(""),
                                  fact.path("object").asText(""));
                    if (!line.isBlank()) {
                        sb.append("- ").append(line).append('\n');
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("unreadable snapshot json: " + e.getMessage(), e);
        }
        return sb.toString().strip();
    }

    private static String spo(String subject, String predicate, String object) {
        StringBuilder sb = new StringBuilder();
        for (String part : List.of(subject, predicate, object)) {
            if (part != null && !part.isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append(" · ");
                }
                sb.append(part);
            }
        }
        return sb.toString();
    }

    /** Системный промпт: A — с блоком памяти, B — тот же каркас без него. */
    static String systemPrompt(String memoryDigest) {
        String base = "You are a helpful assistant. Answer the user's question briefly and factually, "
                + "based on the provided recent conversation.";
        if (memoryDigest == null || memoryDigest.isBlank()) {
            return base;
        }
        return base + "\n\n=== Long-term memory about the user (from earlier conversations) ===\n"
                + memoryDigest;
    }

    /** Пользовательская часть: стенограмма окна (W0, M) + вопрос M (оба плеча видят одинаково). */
    static String userPayload(List<RawMessage> window, String trigger) {
        StringBuilder sb = new StringBuilder("Recent conversation:\n");
        for (RawMessage message : window) {
            sb.append('<').append(role(message.peerName())).append(">: ")
              .append(message.content() == null ? "" : message.content().strip()).append('\n');
        }
        return sb.append("\nQuestion: ").append(trigger == null ? "" : trigger.strip())
                .append("\n\nAnswer:").toString();
    }

    private static String role(String peer) {
        if (peer == null) {
            return "User";
        }
        return switch (peer.toLowerCase()) {
            case "user", "human" -> "User";
            case "assistant", "ai" -> "Assistant";
            default -> peer;
        };
    }

    /** Реплики окна (W0, M): строго между границей W0 и триггером M (§2.9). */
    static List<RawMessage> windowMessages(List<RawMessage> log, long w0, long m) {
        List<RawMessage> window = new ArrayList<>();
        for (RawMessage message : log) {
            if (message.id() > w0 && message.id() < m) {
                window.add(message);
            }
        }
        return window;
    }

    /**
     * Прогон плеч по точкам датасета: `arms [limit]` — limit = сколько новых пар сгенерировать
     * (0/отсутствие = все). Writer-проход §7: канон ответов — gestalt_eval.answers;
     * заморозка кэша = пара (a,b) в PG с совпавшим point_fp (answer_fp + trigger +
     * позиция точки). Смена answer_fp (§7.2) — полная перезапись
     * ответов новым run: старые answers эксперимента сносятся до цикла (слепки
     * переиспользуются); точки без слепка/окна остаются без ответа до появления грида.
     * Прогон оборачивается в run: экономика (llm_calls, токены плеч) видна в runs.
     */
    public static void run(EvalConfig config, int limit) throws Exception {
        if (config.llmApiKey().isBlank()) {
            throw new IllegalStateException("llm.api-key required: arms без LLM бессмысленны");
        }
        LlmClient client = new LlmClient(
                config.llmAnswerBaseUrl(),
                config.llmAnswerApiKey(),
                config.llmAnswerModel(),
                config.llmAnswerReasoningEffort());
        List<EvalPoint> points = EvalDataset.load(EvalPaths.resolve(config.datasetFile()));
        String answerFp = Fingerprints.answerFp(config);

        int generated = 0;
        int cached = 0;
        int skipped = 0;
        long llmCalls = 0;
        long promptTokens = 0;
        long completionTokens = 0;

        try (Connection conn = DriverManager.getConnection(
                config.targetDbUrl(), config.targetDbUser(), config.targetDbPassword())) {
            SchemaMigrator.migrate(conn);
            ExperimentStore experiments = new ExperimentStore(conn);
            String experiment = Experiments.resolveActive(experiments, null);
            RunStore runs = new RunStore(conn);
            ResultStore results = new ResultStore(conn);
            PointSnapshotStore snapshots = new PointSnapshotStore(conn);

            ExperimentStore.Experiment exp = experiments.find(experiment).orElseThrow();
            boolean rewrite = !answerFp.equals(exp.answerFp());
            if (rewrite) {
                experiments.upsert(exp.slug(), exp.material(), exp.datasetRef(), exp.configSnapshot(),
                        exp.ingestFp(), answerFp, exp.status(), exp.note());
                System.out.printf("[ARMS] answer_fp сменился (%s ≠ %s): полная перезапись ответов новым run, "
                                + "слепки переиспользуются (§7.2)%n",
                        Fingerprints.shortFp(exp.answerFp()), Fingerprints.shortFp(answerFp));
            }

            int stale = runs.interruptStale(experiment, "arms");
            if (stale > 0) {
                System.out.printf("[ARMS] %d застрявших running-прогонов arms помечены interrupted (§7.3)%n", stale);
            }
            long runId = runs.start(experiment, "arms",
                    rewrite ? "перезапись ответов: answer_fp сменился" : "ответы → answers (PG) + дамп out/");

            try {
                // кэш плеч (заморозка) нужен только без rewrite: при rewrite старые ответы
                // сносятся до цикла целиком — иначе skipped-точки и хвост limit оставили бы
                // ответы прежнего answer_fp, а упавший прогон сделал бы их «валидным кэшем»
                Map<String, Set<String>> armsByPoint = new java.util.LinkedHashMap<>();
                Map<String, EvalPoint> pointById = new java.util.HashMap<>();
                for (EvalPoint p : points) {
                    pointById.put(p.id(), p);
                }
                if (rewrite) {
                    int purged = results.deleteAnswers(experiment);
                    System.out.printf("[ARMS] rewrite: удалено %d ответов прежнего answer_fp (§7.2)%n", purged);
                } else {
                    // валидность строки = её точка есть в датасете и point_fp совпал
                    // (answer_fp + trigger + позиция): правка trigger в разметке не
                    // оставляет старый ответ молча валидным; осиротевшие строки и чужие
                    // fp исключаются, ниже они перезапишутся или уйдут с rewrite
                    for (ResultStore.AnswerRow row : results.answers(experiment)) {
                        EvalPoint p = pointById.get(row.pointId());
                        if (p == null || row.pointFp() == null
                                || !row.pointFp().equals(Fingerprints.pointFp(answerFp, p))) {
                            continue;
                        }
                        armsByPoint.computeIfAbsent(row.pointId(), k -> new java.util.HashSet<>()).add(row.arm());
                    }
                }

                Map<String, List<RawMessage>> logBySession = new java.util.LinkedHashMap<>();

                for (EvalPoint point : points) {
                    if (limit > 0 && generated >= limit) {
                        break;
                    }
                    Set<String> arms = armsByPoint.getOrDefault(point.id(), Set.of());
                    if (!rewrite && arms.contains("a") && arms.contains("b")) {
                        cached++;
                        continue;
                    }
                    String snapshotJson = snapshots.find(experiment, point.id(), "m").orElse(null);
                    if (snapshotJson == null) {
                        skipped++;
                        System.out.printf("[ARMS] point %s: no snapshot in PG, skipped (run replay first)%n", point.id());
                        continue;
                    }
                    List<RawMessage> log = EvalRunner.sessionLog(logBySession, config, point.sourceSession());
                    int idxM = EvalRunner.indexOfMessage(log, point.sourceMessageId());
                    if (idxM < 0) {
                        skipped++;
                        System.out.printf("[ARMS] point %s: message %d outside the log, skipped%n",
                                point.id(), point.sourceMessageId());
                        continue;
                    }
                    long w0 = EvalRunner.windowStartId(log, idxM, config.windowSize());
                    if (w0 < 0) {
                        skipped++;
                        System.out.printf("[ARMS] point %s: insufficient grid (no W0), skipped%n", point.id());
                        continue;
                    }
                    List<RawMessage> window = windowMessages(log, w0, point.sourceMessageId());
                    String digest = memoryDigest(snapshotJson);
                    if (digest.isBlank()) {
                        System.out.printf("[ARMS] point %s: snapshot is empty — arm A degrades to B%n", point.id());
                    }
                    String payload = userPayload(window, point.trigger());

                    long t0 = System.currentTimeMillis();
                    LlmClient.ChatResult a = client.chatWithUsage(systemPrompt(digest), payload, 0.0);
                    long aLatency = System.currentTimeMillis() - t0;
                    System.out.printf("[ARMS] point %s: arm A answered (%dms, %d tokens)%n",
                            point.id(), aLatency, a.usage().totalTokens());
                    t0 = System.currentTimeMillis();
                    LlmClient.ChatResult b = client.chatWithUsage(systemPrompt(null), payload, 0.0);
                    long bLatency = System.currentTimeMillis() - t0;
                    System.out.printf("[ARMS] point %s: arm B answered (%dms, %d tokens)%n",
                            point.id(), bLatency, b.usage().totalTokens());
                    llmCalls += 2;
                    promptTokens += a.usage().promptTokens() + (long) b.usage().promptTokens();
                    completionTokens += a.usage().completionTokens() + (long) b.usage().completionTokens();

                    OffsetDateTime at = OffsetDateTime.now();
                    String pointFp = Fingerprints.pointFp(answerFp, point);
                    persist(results, experiment, point.id(), "a", pointFp, a, client.model(), aLatency, runId, at);
                    persist(results, experiment, point.id(), "b", pointFp, b, client.model(), bLatency, runId, at);
                    generated++;
                }
                runs.finish(runId, "done", llmCalls, promptTokens, completionTokens);
            } catch (Exception e) {
                runs.finish(runId, "failed", llmCalls, promptTokens, completionTokens);
                throw e;
            }
        }

        System.out.printf("[ARMS] done: %d pair(s) generated, %d cached (PG), %d skipped; "
                        + "LLM calls: %d (prompt %,d, completion %,d tokens)%n",
                generated, cached, skipped, llmCalls, promptTokens, completionTokens);
    }

    /** Канон — answers в PG (§7). */
    private static void persist(ResultStore results, String experiment,
                                String pointId, String arm, String pointFp, LlmClient.ChatResult result, String model,
                                long latencyMs, long runId, OffsetDateTime at) throws Exception {
        results.upsertAnswer(experiment, pointId, arm, pointFp, result.content(), model,
                (long) result.usage().totalTokens(), (long) result.usage().promptTokens(),
                (long) result.usage().completionTokens(), latencyMs, runId, at);
    }

}
