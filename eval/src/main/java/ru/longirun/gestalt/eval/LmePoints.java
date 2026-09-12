package ru.longirun.gestalt.eval;

import ru.longirun.gestalt.eval.ingest.LongMemEvalAdapter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Конвертер LongMemEval → точки датасета (positive control, план 31): вопрос → L1-точка,
 * истина = gt-ответ бенчмарка, must = сам ответ (короткие фактические ответы IE-семейства).
 * Отсев: невекстовые/длинные ответы (> MAX_ANSWER_WORDS слов — must-вхождение нестрого),
 * сессии короче окна + 1 реплики (W0 не существует — replay всё равно отложит точку).
 */
public final class LmePoints {

    static final int MAX_ANSWER_WORDS = 8;

    private LmePoints() {
    }

    /**
     * @param types   множество question_type (например single-session-user);
     * @param limit   максимум точек;
     * @param windowSize размер окна из конфига — для отсева insufficient-grid
     */
    public static List<EvalPoint> convert(LongMemEvalAdapter adapter, Set<String> types,
                                          int limit, int windowSize) throws IOException {
        List<EvalPoint> points = new ArrayList<>();
        adapter.forEachRecord(record -> {
            if (points.size() >= limit) {
                return;
            }
            if (!types.contains(record.questionType())) {
                return;
            }
            String answer = record.answer();
            if (answer.isBlank() || answer.split("\\s+").length > MAX_ANSWER_WORDS) {
                return;
            }
            long totalDialogs = record.haystackSessions().stream().mapToLong(List::size).sum();
            // M = реплика-вопрос (totalDialogs + 1); W0 существует при idxM > windowSize
            if (totalDialogs + 1 <= windowSize + 1) {
                return;
            }
            points.add(new EvalPoint(
                    "LME-" + record.questionId(),
                    "L1",
                    LongMemEvalAdapter.SESSION_PREFIX + record.questionId(),
                    totalDialogs + 1,
                    record.question(),
                    answer,
                    List.of(answer),
                    List.of(),
                    "lme:" + record.questionType(),
                    null));
        });
        return points;
    }
}
