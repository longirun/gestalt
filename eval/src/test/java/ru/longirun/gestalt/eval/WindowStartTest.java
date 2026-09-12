package ru.longirun.gestalt.eval;

import org.junit.jupiter.api.Test;

import ru.longirun.gestalt.eval.ingest.RawMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * window_start(M) = последняя реплика ВНЕ окна (§2.9, правка off-by-one 2026-09-11):
 * окно плеч — (W0, M), N реплик; инвариант «max evidence ≤ W0» ⇔ «происхождение вне окна».
 */
class WindowStartTest {

    private static List<RawMessage> log(long... ids) {
        return java.util.Arrays.stream(ids)
                .mapToObj(id -> new RawMessage(id, "s", "user", "c", 1, null))
                .toList();
    }

    @Test
    void windowStartIsLastMessageOutsideWindow() {
        // лог с дырами в id: W0 определяется позицией, окно (W0, M) = N реплик до M
        List<RawMessage> log = log(1, 3, 5, 7, 9, 11, 13, 15, 17, 19);

        // M = idx 9 (id 19), N = 5: окно = idx 4..8, W0 = idx 3 (id 7)
        assertEquals(7, EvalRunner.windowStartId(log, 9, 5));
        // первая позиция, где W0 существует (M = idx 6, W0 = idx 0)
        assertEquals(1, EvalRunner.windowStartId(log, 6, 5));
    }

    @Test
    void noWindowStartNearLogHead() {
        List<RawMessage> log = log(1, 3, 5, 7, 9, 11);

        // M среди первых N+1 реплик — слепка W0 не существует
        assertEquals(-1, EvalRunner.windowStartId(log, 5, 5));
        assertEquals(-1, EvalRunner.windowStartId(log, 0, 5));
        // граница: idxM = N — до окна нет ни одной реплики, W0 не существует
        assertEquals(-1, EvalRunner.windowStartId(log, 1, 1));
    }

    @Test
    void windowOfOneUsesHeadMessageAsW0() {
        List<RawMessage> log = log(1, 3, 5);

        // N = 1: окно = ровно одна реплика перед M (idx 1), W0 = idx 0
        assertEquals(1, EvalRunner.windowStartId(log, 2, 1));
    }
}
