package ru.longirun.gestalt.eval;

import com.fasterxml.jackson.databind.ObjectMapper;

import ru.longirun.gestalt.eval.store.ExperimentStore;
import ru.longirun.gestalt.eval.store.ResultStore;
import ru.longirun.gestalt.eval.store.RunStore;
import ru.longirun.gestalt.eval.store.SchemaMigrator;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Стадия E4 — машинные оракулы (ADR 28 §3.4): проверка ответов плеч по must/mustNot
 * без LLM-судьи (детерминированные, ярус smoke; судьи — полный ярус). Вердикт плеча:
 * pass = все must найдены и ни один mustNot не найден в ответе. Матчинг маркера —
 * регистронезависимое вхождение с границами слов: «18» совпадает с «18 minutes»,
 * но не с «18th» (урок E2-PC: строгая подстрока давала ложные позитивы на числах).
 * C-точки: появление mustNot-маркера в ответе плеча A = leak (veto Р36, в lift не входит).
 * Контракт вывода — verdicts (PG); viewer читает /api/oracles.
 */
public final class Oracles {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Вердикт одного плеча: pass, какие must не найдены, какие mustNot найдены (утечка). */
    public record ArmVerdict(boolean pass, List<String> missedMust, List<String> matchedMustNot) {
    }

    /** Запись по точке: вердикты плеч + leak-флаг C-точки (маркеры утекли в ответ A). */
    public record PointVerdict(
            String pointId, String level, ArmVerdict a, ArmVerdict b,
            boolean leak, List<String> leakMarkers) {
    }

    private Oracles() {
    }

    /** Маркер найден в ответе. Языко-независимая нижняя граница (smoke-ярус): lowercase,
     * ё→е, срез markdown-разметки (урок LME-3d86fd0a: «a **coffee shop**»), вхождение
     * с Unicode-границами слов ([\p{L}\p{N}] — ловля ревью 2026-09-14: ASCII-only класс
     * [A-Za-z0-9] считал кириллицу разделителем — «тест» ложно матчился в «тесты»,
     * «протест») — «18» не совпадает с «180», «тест» не совпадает с «тестирование».
     * Морфологию (ординалы, числа, артикли, местоимения) и парафразы сознательно НЕ
     * закрываем: завтра материал будет по-русски и на других языках — это ярус
     * LLM-судьи, не матчера. */
    static boolean matches(String answer, String marker) {
        if (answer == null || marker == null || marker.isBlank()) {
            return false;
        }
        String haystack = answer.toLowerCase().replace('ё', 'е').replaceAll("[*_`#\"]+", "");
        String needle = marker.toLowerCase().replace('ё', 'е').strip();
        if (needle.isBlank()) {
            return false;
        }
        Pattern p = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(needle) + "(?![\\p{L}\\p{N}])");
        return p.matcher(haystack).find();
    }

    static ArmVerdict verdict(String answer, List<String> must, List<String> mustNot) {
        List<String> missedMust = new ArrayList<>();
        for (String marker : must) {
            if (!matches(answer, marker)) {
                missedMust.add(marker);
            }
        }
        List<String> matchedMustNot = new ArrayList<>();
        for (String marker : mustNot) {
            if (matches(answer, marker)) {
                matchedMustNot.add(marker);
            }
        }
        return new ArmVerdict(missedMust.isEmpty() && matchedMustNot.isEmpty(), missedMust, matchedMustNot);
    }

    /**
     * Прогон оракулов по точкам с материализованными ответами плеч (gestalt_eval.answers,
     * §7 writer): точки без пары ответов пропускаются (плечи не прогонялись). Вердикты
     * пишутся в verdicts (PG — источник истины; viewer и E5 читают PG).
     * Прогон оборачивается в run (история попыток).
     */
    public static void run(EvalConfig config, String experimentSlug) throws Exception {
        List<EvalPoint> points = EvalDataset.load(EvalPaths.resolve(config.datasetFile()));

        int checked = 0;
        int skipped = 0;
        try (Connection conn = DriverManager.getConnection(
                config.targetDbUrl(), config.targetDbUser(), config.targetDbPassword())) {
            SchemaMigrator.migrate(conn);
            ExperimentStore experiments = new ExperimentStore(conn);
            String experiment = Experiments.resolveActive(experiments, experimentSlug);
            RunStore runs = new RunStore(conn);
            ResultStore results = new ResultStore(conn);

            int stale = runs.interruptStale(experiment, "oracles");
            if (stale > 0) {
                System.out.printf("[ORACLE] %d застрявших running-прогонов oracles помечены interrupted (§7.3)%n", stale);
            }
            long runId = runs.start(experiment, "oracles", "машинные оракулы: ответы PG → вердикты PG");
            try {
                Map<String, ResultStore.AnswerRow> byArm = new HashMap<>();
                for (ResultStore.AnswerRow row : results.answers(experiment)) {
                    byArm.put(row.pointId() + "|" + row.arm(), row);
                }

                for (EvalPoint point : points) {
                    ResultStore.AnswerRow a = byArm.get(point.id() + "|a");
                    ResultStore.AnswerRow b = byArm.get(point.id() + "|b");
                    if (a == null || b == null) {
                        skipped++;
                        continue;
                    }

                    ArmVerdict av = verdict(a.answer(), point.must(), point.mustNot());
                    ArmVerdict bv = verdict(b.answer(), point.must(), point.mustNot());
                    boolean leak = "C".equals(point.level()) && !av.matchedMustNot().isEmpty();
                    PointVerdict verdict = new PointVerdict(point.id(), point.level(), av, bv,
                            leak, av.matchedMustNot());

                    results.upsertVerdict(experiment, point.id(), av.pass(), bv.pass(), leak,
                            MAPPER.writeValueAsString(verdict));
                    checked++;
                    String solved = (av.pass() != bv.pass()) ? "решённая пара (" + (av.pass() ? "A" : "B") + ")" : "не решённая";
                    System.out.printf("[ORACLE] point %s: A %s (missed %d) / B %s (missed %d) — %s%s%n",
                            point.id(), av.pass() ? "pass" : "fail", av.missedMust().size(),
                            bv.pass() ? "pass" : "fail", bv.missedMust().size(), solved,
                            leak ? " · C-LEAK (veto)" : "");
                }

                runs.finish(runId, "done");
            } catch (Exception e) {
                runs.finish(runId, "failed");
                throw e;
            }
        }

        System.out.printf("[ORACLE] done: %d checked, %d skipped (no answers)%n",
                checked, skipped);
    }
}
