package ru.longirun.gestalt.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W-проверка (план 31 §2.9): вердикты фактов по evidence относительно W0
 * (последняя реплика вне окна): valid / in-window / lag / unknown.
 */
class WCheckTest {

    private static String snapshot(String... factIds) {
        return snapshotWithStatement(null, factIds);
    }

    private static String snapshotWithStatement(String statement, String... factIds) {
        StringBuilder sb = new StringBuilder("{\"critical\":[");
        for (int i = 0; i < factIds.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":\"").append(factIds[i]).append("\",")
              .append("\"subject\":\"s\",\"predicate\":\"p\",\"object\":\"o\"");
            if (statement != null) {
                sb.append(",\"statement\":\"").append(statement).append("\"");
            }
            sb.append('}');
        }
        return sb.append("]}").toString();
    }

    @Test
    void factBornOutsideWindowIsValid() {
        UUID fact = UUID.randomUUID();
        Map<UUID, List<Long>> evidence = Map.of(fact, List.of(10L, 12L));

        WCheck.PointReport report = WCheck.check("L1-x", List.of(), snapshot(fact.toString()), 15, 20, evidence::get);

        assertTrue(report.covered());
        assertEquals(1, report.valid().size());
        assertEquals(WCheck.VALID, report.valid().getFirst().verdict());
        assertEquals(10, report.valid().getFirst().born());
        assertEquals(12, report.valid().getFirst().last());
    }

    @Test
    void factBornInsideWindowIsInWindow() {
        UUID fact = UUID.randomUUID();
        Map<UUID, List<Long>> evidence = Map.of(fact, List.of(18L));

        WCheck.PointReport report = WCheck.check("L1-x", List.of(), snapshot(fact.toString()), 15, 20, evidence::get);

        assertFalse(report.covered());
        assertEquals(1, report.inWindow().size());
    }

    @Test
    void loneLagFactDoesNotCoverPoint() {
        // регресс review.txt №1: факт родился до окна и усилен в окне — даже если он
        // физически присутствовал в слепке W0, повтор из окна раскрывает его плечу B
        UUID fact = UUID.randomUUID();
        Map<UUID, List<Long>> evidence = Map.of(fact, List.of(10L, 18L));

        WCheck.PointReport report = WCheck.check("L1-x", List.of(), snapshot(fact.toString()), 15, 20, evidence::get);

        assertFalse(report.covered());
        assertEquals(1, report.lag().size());
        assertTrue(report.valid().isEmpty());
    }

    @Test
    void missingEvidenceIsUnknownNotValid() {
        // регресс review.txt №2: пустой evidence — происхождение неизвестно, не валидно
        UUID fact = UUID.randomUUID();

        WCheck.PointReport report = WCheck.check("L1-x", List.of(), snapshot(fact.toString()), 15, 20, id -> List.of());

        assertFalse(report.covered());
        assertEquals(1, report.unknown().size());
        assertEquals(WCheck.UNKNOWN, report.unknown().getFirst().verdict());
        assertTrue(report.valid().isEmpty());
    }

    @Test
    void emptySnapshotIsNotCovered() {
        WCheck.PointReport report = WCheck.check("L1-x", List.of(), snapshot(), 15, 20, id -> List.of());

        assertFalse(report.covered());
        assertTrue(report.valid().isEmpty());
    }

    @Test
    void mustInValidFactAnswerCoversPoint() {
        UUID fact = UUID.randomUUID();
        Map<UUID, List<Long>> evidence = Map.of(fact, List.of(10L));

        WCheck.PointReport report = WCheck.check("L1-x", List.of("Business Administration"),
                snapshotWithStatement("User holds a degree in Business Administration.", fact.toString()),
                15, 20, evidence::get);

        assertTrue(report.covered());
        assertTrue(report.answerCovered());
    }

    @Test
    void mustMatchIsCaseInsensitive() {
        UUID fact = UUID.randomUUID();
        Map<UUID, List<Long>> evidence = Map.of(fact, List.of(10L));

        WCheck.PointReport report = WCheck.check("L1-x", List.of("golden retriever"),
                snapshotWithStatement("User adopted a Golden Retriever puppy.", fact.toString()),
                15, 20, evidence::get);

        assertTrue(report.answerCovered());
    }

    @Test
    void mustHostedByInWindowFactDoesNotCoverAnswer() {
        // ответ-факт есть в слепке, но рождение в окне: covered может быть true
        // (есть другой valid-факт), а answerCovered — false
        UUID inWindowFact = UUID.randomUUID();
        UUID validFact = UUID.randomUUID();
        Map<UUID, List<Long>> evidence = Map.of(
                inWindowFact, List.of(18L),
                validFact, List.of(10L));
        String snapshot = "{\"critical\":["
                + "{\"id\":\"" + inWindowFact + "\",\"subject\":\"s\",\"predicate\":\"p\","
                + "\"object\":\"o\",\"statement\":\"bought a yellow dress\"},"
                + "{\"id\":\"" + validFact + "\",\"subject\":\"s\",\"predicate\":\"p\",\"object\":\"o\"}"
                + "]}";

        WCheck.PointReport report = WCheck.check("L1-x", List.of("yellow dress"),
                snapshot, 15, 20, evidence::get);

        assertTrue(report.covered());
        assertFalse(report.answerCovered());
    }

    @Test
    void allMustsMustBePresentForAnswerCoverage() {
        UUID fact = UUID.randomUUID();
        Map<UUID, List<Long>> evidence = Map.of(fact, List.of(10L));

        WCheck.PointReport report = WCheck.check("L1-x", List.of("University of Melbourne", "Australia"),
                snapshotWithStatement("studied abroad at the University of Melbourne", fact.toString()),
                15, 20, evidence::get);

        assertFalse(report.answerCovered());
    }

    @Test
    void emptyMustNeverAnswerCovers() {
        UUID fact = UUID.randomUUID();
        Map<UUID, List<Long>> evidence = Map.of(fact, List.of(10L));

        WCheck.PointReport report = WCheck.check("L1-x", List.of(),
                snapshotWithStatement("any statement", fact.toString()),
                15, 20, evidence::get);

        assertTrue(report.covered());
        assertFalse(report.answerCovered());
    }
}
