package ru.longirun.gestalt.eval;

import ru.longirun.gestalt.eval.portrait.SnapshotDiff.FactRef;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * W-проверка точки (план 31 §2.9): валидность покрытия определяется происхождением
 * факта, а не слепком — max evidence-id факта ≤ W0 (последняя реплика вне окна).
 * Max, а не рождение: reinforcement из видимой плечами реплики окна тоже раскрывает
 * происхождение факта плечу B, поэтому born ≤ W0 < last относится к lag, а не к valid.
 * Covered = есть хотя бы один valid-факт: присутствие в слепке W0 не спасает
 * (lag-факт есть в W0-слепке, но раскрыт плечу B повтором из окна), а пустой evidence —
 * неизвестное происхождение (unknown), не валидность.
 * AnswerCovered — must-анкерованное покрытие: каждый must-маркер точки найден
 * (подстрока, регистронезависимо) в valid-факте (statement+predicate+object);
 * честная нижняя граница для E2-PC («Encoding извлекает факты-ответы»): лексические
 * парафразы (иной порядок слов) считаются промахом — материал для ручного разбора.
 */
public final class WCheck {

    public static final String VALID = "valid";
    public static final String IN_WINDOW = "in-window";
    public static final String LAG = "lag";
    public static final String UNKNOWN = "unknown";

    public record FactReport(
            String factId, String section, String subject, String predicate,
            String object, String statement, List<Long> evidence,
            long born, long last, String verdict) {
    }

    public record PointReport(
            String pointId, long w0MessageId, long mMessageId, boolean covered,
            boolean answerCovered,
            List<FactReport> valid, List<FactReport> inWindow, List<FactReport> lag,
            List<FactReport> unknown) {
    }

    private WCheck() {
    }

    /**
     * @param evidenceLookup max-достоверный источник evidence факта (facts.evidence из БД);
     *                       для факта без evidence возвращает пустой список
     */
    public static PointReport check(String pointId, List<String> must, String mSnapshotJson,
                                    long w0MessageId, long mMessageId,
                                    Function<UUID, List<Long>> evidenceLookup) {
        List<FactRef> mFacts = snapshotFacts(mSnapshotJson);

        List<FactReport> valid = new ArrayList<>();
        List<FactReport> inWindow = new ArrayList<>();
        List<FactReport> lag = new ArrayList<>();
        List<FactReport> unknown = new ArrayList<>();
        for (FactRef fact : mFacts) {
            List<Long> evidence = evidenceLookup.apply(fact.id());
            long born = evidence.stream().mapToLong(Long::longValue).min().orElse(-1);
            long last = evidence.stream().mapToLong(Long::longValue).max().orElse(-1);

            String verdict;
            if (evidence.isEmpty()) {
                verdict = UNKNOWN;
            } else if (born <= w0MessageId && last <= w0MessageId) {
                verdict = VALID;
            } else if (born > w0MessageId) {
                verdict = IN_WINDOW;
            } else {
                verdict = LAG;
            }
            FactReport report = new FactReport(
                    fact.id().toString(), fact.section(), fact.subject(), fact.predicate(),
                    fact.object(), fact.statement(), evidence, born, last, verdict);
            switch (verdict) {
                case VALID -> valid.add(report);
                case IN_WINDOW -> inWindow.add(report);
                case LAG -> lag.add(report);
                default -> unknown.add(report);
            }
        }
        String validHaystack = valid.stream()
                .map(f -> (f.statement() == null ? "" : f.statement()) + " "
                        + f.predicate() + " " + f.object())
                .map(String::toLowerCase)
                .reduce("", (a, b) -> a + " " + b)
                .replaceAll("\\s+", " ");
        boolean answerCovered = !must.isEmpty() && must.stream()
                .allMatch(marker -> validHaystack.contains(marker.toLowerCase()));
        return new PointReport(pointId, w0MessageId, mMessageId, !valid.isEmpty(),
                answerCovered, valid, inWindow, lag, unknown);
    }

    private static List<FactRef> snapshotFacts(String snapshotJson) {
        return ru.longirun.gestalt.eval.portrait.SnapshotDiff.newFacts("{}", snapshotJson);
    }
}
