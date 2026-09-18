package ru.longirun.gestalt.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.longirun.gestalt.eval.store.CheckpointStore;
import ru.longirun.gestalt.eval.store.ExperimentStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lifecycle-протокол реестра (план 31 §7.2, writer-проход): replay решает «продолжать
 * или с нуля» по ingest_fp — совпал с active-экспериментом → продолжаем (новый run),
 * сменился → новый эксперимент (старый archived, read-only) с re-replay с нуля:
 * сбрасываем чекпоинты и факты owner/project датасета (слепки старого уже канонизированы
 * в snapshots и переживают сброс). answer_fp ответы не охраняет экспериментом: смена —
 * перезапись ответов новым run (см. Arms). Явное действие владельца — `replay <new-slug>`;
 * без слага прибор только диагностирует расхождение, ничего не трогая.
 */
public final class Experiments {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Experiments() {
    }

    /**
     * Слаг не задан → единственный активный эксперимент реестра; задан → должен существовать.
     * Ноль/несколько активных или неизвестный слаг — ошибка со списком кандидатов.
     */
    public static String resolveActive(ExperimentStore experiments, String slugOrNull) throws Exception {
        if (slugOrNull != null && !slugOrNull.isBlank()) {
            return experiments.find(slugOrNull).map(ExperimentStore.Experiment::slug)
                    .orElseThrow(() -> new IllegalStateException("эксперимент '%s' не найден в реестре: %s"
                            .formatted(slugOrNull, knownSlugs(experiments))));
        }
        List<ExperimentStore.Experiment> active = experiments.byStatus("active");
        if (active.size() != 1) {
            throw new IllegalStateException("активных экспериментов %d (нужно ровно 1 или задай слаг): %s"
                    .formatted(active.size(), knownSlugs(experiments)));
        }
        return active.getFirst().slug();
    }

    /**
     * Целевой эксперимент для replay (§7.2): slug задан — найти/создать; не задан —
     * единственный active с совпадающим ingest_fp. Создание нового — явное действие
     * (`replay <slug>`): прочие active уходят в archived, инжест-состояние сбрасывается.
     */
    public static String forReplay(Connection conn, EvalConfig config,
                                   List<EvalPoint> points, String slugArg) throws Exception {
        ExperimentStore experiments = new ExperimentStore(conn);
        String ingestFp = Fingerprints.ingestFp(config, points);
        String answerFp = Fingerprints.answerFp(config);
        String snapshot = configSnapshot(config);

        if (slugArg != null && !slugArg.isBlank()) {
            var existing = experiments.find(slugArg);
            if (existing.isEmpty()) {
                create(conn, experiments, slugArg, config, points, snapshot, ingestFp, answerFp);
                return slugArg;
            }
            ExperimentStore.Experiment experiment = existing.get();
            if ("archived".equals(experiment.status())) {
                throw new IllegalStateException("эксперимент '%s' archived (read-only, §7.3): продолжение запрещено — "
                        .formatted(slugArg) + "слепки/вердикты читаются, записывать может только active");
            }
            if (!ingestFp.equals(experiment.ingestFp())) {
                throw new IllegalStateException(("эксперимент '%s' построен с другим ingest_fp (%s ≠ %s): "
                        + "смена инжест-конфига = новый эксперимент (§7.2), задай свежий слаг")
                        .formatted(slugArg, Fingerprints.shortFp(experiment.ingestFp()), Fingerprints.shortFp(ingestFp)));
            }
            return slugArg;
        }

        List<ExperimentStore.Experiment> active = experiments.byStatus("active");
        if (active.isEmpty()) {
            throw new IllegalStateException("нет активных экспериментов: `replay <slug>` создаст новый (§7.2); "
                    + "реестр: " + knownSlugs(experiments));
        }
        if (active.size() > 1) {
            throw new IllegalStateException("активных экспериментов %d (нужно ровно 1): %s"
                    .formatted(active.size(), knownSlugs(experiments)));
        }
        ExperimentStore.Experiment experiment = active.getFirst();
        if (experiment.ingestFp() != null && experiment.ingestFp().equals(ingestFp)) {
            return experiment.slug();
        }
        throw new IllegalStateException(("ingest-конфиг сменился с момента создания '%s' (%s ≠ %s): "
                + "новый эксперимент = `replay <new-slug>` — '%s' уйдёт в archived, инжест начнётся с нуля (§7.2)")
                .formatted(experiment.slug(), Fingerprints.shortFp(experiment.ingestFp()),
                        Fingerprints.shortFp(ingestFp), experiment.slug()));
    }

    /** Создание эксперимента: прочие active → archived, сброс инжест-состояния датасета. */
    private static void create(Connection conn, ExperimentStore experiments, String slug,
                               EvalConfig config, List<EvalPoint> points,
                               String snapshot, String ingestFp, String answerFp) throws Exception {
        int archived = 0;
        for (ExperimentStore.Experiment other : experiments.byStatus("active")) {
            experiments.upsert(other.slug(), other.material(), other.datasetRef(), other.configSnapshot(),
                    other.ingestFp(), other.answerFp(), "archived", other.note());
            archived++;
            System.out.printf("[EXP] experiment %s → archived (сменился ingest_fp, §7.2)%n", other.slug());
        }
        experiments.upsert(slug, material(config, points), config.datasetFile(), snapshot,
                ingestFp, answerFp, "active", "создан replay (writer §7.2)");
        int resetFacts = resetIngestState(conn, config, points);
        System.out.printf("[EXP] experiment %s: создан (material %s, ingest_fp %s), archived %d, "
                        + "инжест-состояние сброшено: %d фактов owner/project удалено, чекпоинты сессий снесены — "
                        + "re-replay с нуля%n",
                slug, material(config, points), Fingerprints.shortFp(ingestFp), archived, resetFacts);
    }

    /** Сброс приборного состояния для re-replay с нуля: факты owner/project и чекпоинты сессий датасета. */
    private static int resetIngestState(Connection conn, EvalConfig config, List<EvalPoint> points) throws Exception {
        Map<String, String> ownerByProject = new LinkedHashMap<>();
        for (EvalPoint point : points) {
            ownerByProject.putIfAbsent(EvalRunner.portraitProject(config, point.sourceSession()),
                    EvalRunner.portraitOwner(config, point.sourceSession()));
        }
        int deletedFacts = 0;
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM facts WHERE owner_id = ? AND project_id = ?")) {
            for (Map.Entry<String, String> e : ownerByProject.entrySet()) {
                ps.setString(1, e.getValue());
                ps.setString(2, e.getKey());
                deletedFacts += ps.executeUpdate();
            }
        }
        CheckpointStore checkpoints = new CheckpointStore(conn);
        for (EvalPoint point : points) {
            checkpoints.reset("session:" + point.sourceSession());
        }
        return deletedFacts;
    }

    /** Материал по фактическому составу датасета: lme / live/<session> / mixed. */
    static String material(EvalConfig config, List<EvalPoint> points) {
        boolean hasLme = points.stream().anyMatch(p -> EvalRunner.isLme(config, p.sourceSession()));
        String firstLive = points.stream()
                .map(EvalPoint::sourceSession)
                .filter(s -> !EvalRunner.isLme(config, s))
                .findFirst().orElse("");
        if (hasLme && firstLive.isEmpty()) {
            return "lme";
        }
        return firstLive.isEmpty() ? "unknown" : "live/" + firstLive + (hasLme ? "+lme" : "");
    }

    /** Снимок конфига прибора для config_snapshot (что влияло на результат, §7.1). */
    static String configSnapshot(EvalConfig config) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("llm.model", config.llmModel());
        node.put("llm.reasoning-effort", config.llmReasoningEffort());
        node.put("llm.answer.model", config.llmAnswerModel());
        node.put("llm.answer.reasoning-effort", config.llmAnswerReasoningEffort());
        node.put("window.size", config.windowSize());
        node.put("batch.max-messages", config.batchMaxMessages());
        node.put("batch.max-tokens", config.batchMaxTokens());
        node.put("dataset.file", config.datasetFile());
        return node.toString();
    }

    private static String knownSlugs(ExperimentStore experiments) {
        try {
            return experiments.list().stream()
                    .map(e -> e.slug() + (e.status().equals("active") ? "*" : ""))
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("реестр пуст — `replay <slug>` создаст первый эксперимент");
        } catch (Exception e) {
            return "реестр недоступен: " + e.getMessage();
        }
    }
}
