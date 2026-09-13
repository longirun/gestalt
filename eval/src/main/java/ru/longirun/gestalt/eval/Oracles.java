package ru.longirun.gestalt.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Стадия E4 — машинные оракулы (ADR 28 §3.4): проверка ответов плеч по must/mustNot
 * без LLM-судьи (детерминированные, ярус smoke; судьи — полный ярус). Вердикт плеча:
 * pass = все must найдены и ни один mustNot не найден в ответе. Матчинг маркера —
 * регистронезависимое вхождение с границами слов: «18» совпадает с «18 minutes»,
 * но не с «18th» (урок E2-PC: строгая подстрока давала ложные позитивы на числах).
 * C-точки: появление mustNot-маркера в ответе плеча A = leak (veto Р36, в lift не входит).
 * Контракт вывода — viewer (/api/oracles): out/oracles.jsonl.
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
     * с границами слов ([A-Za-z0-9]) — «18» не совпадает с «180». Морфологию (ординалы,
     * числа, артикли, местоимения) и парафразы сознательно НЕ закрываем: завтра материал
     * будет по-русски и на других языках — это ярус LLM-судьи, не матчера. */
    static boolean matches(String answer, String marker) {
        if (answer == null || marker == null || marker.isBlank()) {
            return false;
        }
        String haystack = answer.toLowerCase().replace('ё', 'е').replaceAll("[*_`#\"]+", "");
        String needle = marker.toLowerCase().replace('ё', 'е').strip();
        if (needle.isBlank()) {
            return false;
        }
        Pattern p = Pattern.compile("(?<![A-Za-z0-9])" + Pattern.quote(needle) + "(?![A-Za-z0-9])");
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
     * Прогон оракулов по точкам с материализованными ответами плеч (out/answers);
     * точки без пары ответов пропускаются (плечи не прогонялись). Отчёт перезаписывается
     * в out/oracles.jsonl (viewer и E5 читают его).
     */
    public static void run(EvalConfig config) throws Exception {
        List<EvalPoint> points = EvalDataset.load(EvalPaths.resolve(config.datasetFile()));
        Path answersDir = EvalPaths.evalDir().resolve("out/answers");
        Path outFile = EvalPaths.evalDir().resolve("out/oracles.jsonl");

        StringBuilder out = new StringBuilder();
        int checked = 0;
        int skipped = 0;
        for (EvalPoint point : points) {
            Path aFile = answersDir.resolve(point.id() + ".a.json");
            Path bFile = answersDir.resolve(point.id() + ".b.json");
            if (!Files.exists(aFile) || !Files.exists(bFile)) {
                skipped++;
                continue;
            }
            String answerA = MAPPER.readTree(Files.readString(aFile)).path("answer").asText("");
            String answerB = MAPPER.readTree(Files.readString(bFile)).path("answer").asText("");

            ArmVerdict a = verdict(answerA, point.must(), point.mustNot());
            ArmVerdict b = verdict(answerB, point.must(), point.mustNot());
            boolean leak = "C".equals(point.level()) && !a.matchedMustNot().isEmpty();
            PointVerdict verdict = new PointVerdict(point.id(), point.level(), a, b,
                    leak, a.matchedMustNot());

            out.append(MAPPER.writeValueAsString(verdict)).append('\n');
            checked++;
            String solved = (a.pass() != b.pass()) ? "решённая пара (" + (a.pass() ? "A" : "B") + ")" : "не решённая";
            System.out.printf("[ORACLE] point %s: A %s (missed %d) / B %s (missed %d) — %s%s%n",
                    point.id(), a.pass() ? "pass" : "fail", a.missedMust().size(),
                    b.pass() ? "pass" : "fail", b.missedMust().size(), solved,
                    leak ? " · C-LEAK (veto)" : "");
        }

        Files.createDirectories(outFile.getParent());
        Files.writeString(outFile, out.toString());
        System.out.printf("[ORACLE] done: %d checked, %d skipped (no answers) -> %s%n",
                checked, skipped, outFile.normalize());
    }

    /** Для E5: ответ из файла out/answers/<pointId>.<arm>.json (null, если файла нет). */
    static String readAnswer(Path answersDir, String pointId, String arm) throws Exception {
        Path file = answersDir.resolve(pointId + "." + arm + ".json");
        if (!Files.exists(file)) {
            return null;
        }
        JsonNode node = MAPPER.readTree(Files.readString(file));
        return node.path("answer").asText("");
    }
}
