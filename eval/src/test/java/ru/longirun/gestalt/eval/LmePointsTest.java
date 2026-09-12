package ru.longirun.gestalt.eval;

import org.junit.jupiter.api.Test;
import ru.longirun.gestalt.eval.ingest.LongMemEvalAdapter;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LmePointsTest {

    private final LongMemEvalAdapter adapter = new LongMemEvalAdapter(
            Path.of("src/test/resources/lme/minimal.json"));

    @Test
    void convertsFilteredSingleSessionUser() throws Exception {
        List<EvalPoint> points = LmePoints.convert(adapter, Set.of("single-session-user"), 10, 2);

        // q_beta отсеян типом (knowledge-update), q_gamma — длинным ответом (> 8 слов)
        assertEquals(1, points.size());
        EvalPoint point = points.getFirst();
        assertEquals("LME-q_alpha", point.id());
        assertEquals("L1", point.level());
        assertEquals("lme-q_alpha", point.sourceSession());
        assertEquals(7, point.sourceMessageId(), "M = реплика-вопрос, последняя в логе");
        assertEquals("What is my favorite color?", point.trigger());
        assertEquals("Blue", point.truth());
        assertEquals(List.of("Blue"), point.must());
        assertEquals("lme:single-session-user", point.pattern());
        assertNull(point.coverage());
    }

    @Test
    void limitBoundsOutput() throws Exception {
        assertEquals(1, LmePoints.convert(adapter, Set.of("single-session-user"), 1, 2).size());
        assertEquals(0, LmePoints.convert(adapter, Set.of("temporal-reasoning"), 10, 2).size());
    }
}
