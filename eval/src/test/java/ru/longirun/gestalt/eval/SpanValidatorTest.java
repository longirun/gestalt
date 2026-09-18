package ru.longirun.gestalt.eval;

import org.junit.jupiter.api.Test;

import ru.longirun.gestalt.eval.ingest.RawMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Спан-инварианты И1–И3, И5 (спека 33 §4): trigger ⊆ реплика M, truth ⊆ реплика-якорь,
 * маркеры ветви вне (W0, M] (W0 исключён — инжестируется в память; M включён — giveaway),
 * mustNot не матчится внутри must, якорь истины строго после M.
 * Вердикты ok/span-miss/in-window/marker-collision/causality/unanchored/skip.
 * Лог: id 1..12, M = 10 (idx 9), окно N = 3 → реплики 7..10, W0 = 6.
 */
class SpanValidatorTest {

    private static final int WINDOW = 3;

    private static RawMessage msg(long id, String content) {
        return new RawMessage(id, "s", "user", content, 1, null);
    }

    /** Базовый лог: нейтральное заполнение, маркеры и спаны вносятся точечно. */
    private static List<RawMessage> baseLog() {
        return new java.util.ArrayList<>(java.util.stream.LongStream.rangeClosed(1, 12)
                .mapToObj(id -> msg(id, "заполнение " + id))
                .toList());
    }

    private static void set(List<RawMessage> log, long id, String content) {
        for (int i = 0; i < log.size(); i++) {
            if (log.get(i).id() == id) {
                log.set(i, msg(id, content));
                return;
            }
        }
        throw new IllegalArgumentException("no message " + id);
    }

    private static EvalPoint l1(String trigger, String truth, List<String> must, Long truthMessageId) {
        return l1(trigger, truth, must, List.of(), truthMessageId);
    }

    private static EvalPoint l1(String trigger, String truth, List<String> must, List<String> mustNot,
                                Long truthMessageId) {
        return new EvalPoint("P", "L1", "s", 10, trigger, truth, must, mustNot,
                "pattern", "coverage", "tech-choice", truthMessageId);
    }

    private static EvalPoint c(String trigger, List<String> mustNot) {
        return new EvalPoint("P", "C", "s", 10, trigger, null, List.of(), mustNot,
                null, null, null, null);
    }

    private static SpanValidator.PointVerdict check(EvalPoint point, List<RawMessage> log) {
        return SpanValidator.validate(point, log, WINDOW, false);
    }

    @Test
    void l1PointWithCleanSpansAndMarkersPasses() {
        List<RawMessage> log = baseLog();
        set(log, 10, "Как настроить сборку? Нужен совет.");
        set(log, 12, "Сделал через Gradle version catalog.");

        assertEquals(SpanValidator.OK, check(l1("Как настроить сборку?", "Gradle version catalog",
                List.of("docker"), 12L), log).status());
    }

    @Test
    void spanMatchingSurvivesCaseAndYo() {
        List<RawMessage> log = baseLog();
        set(log, 10, "Почему Всё ломается при сборке?");
        set(log, 12, "Потому что лед в конфиге");

        assertEquals(SpanValidator.OK, check(l1("почему всё ломается", "лед в конфиге",
                List.of(), 12L), log).status());
    }

    @Test
    void rephrasedTriggerIsSpanMiss() {
        List<RawMessage> log = baseLog();
        set(log, 10, "Как настроить сборку?");
        set(log, 12, "Сделал через Gradle.");

        var verdict = check(l1("хочу совет по сборке проекта", "Сделал через Gradle.",
                List.of(), 12L), log);
        assertEquals(SpanValidator.SPAN_MISS, verdict.status());
        assertTrue(verdict.details().get(0).contains("trigger"));
    }

    @Test
    void truthOutsideCarrierMessageIsSpanMiss() {
        List<RawMessage> log = baseLog();
        set(log, 10, "вопрос");
        set(log, 12, "ответ");

        var verdict = check(l1("вопрос", "цитата, которой нет в логе", List.of(), 12L), log);
        assertEquals(SpanValidator.SPAN_MISS, verdict.status());
        assertTrue(verdict.details().get(0).contains("truth"));
    }

    @Test
    void anchorMessageOutsideLogSliceIsSpanMiss() {
        var verdict = check(l1("заполнение 10", "что угодно", List.of(), 99L), baseLog());
        assertEquals(SpanValidator.SPAN_MISS, verdict.status());
        assertTrue(verdict.details().get(0).contains("99"));
    }

    @Test
    void mOutsideLogSliceIsSpanMiss() {
        EvalPoint orphan = new EvalPoint("P", "L1", "s", 99, "вопрос", null, List.of(), List.of(),
                null, null, null, null);
        var verdict = check(orphan, baseLog());
        assertEquals(SpanValidator.SPAN_MISS, verdict.status());
        assertTrue(verdict.details().get(0).contains("99"));
    }

    @Test
    void markerInsideWindowIsHardReject() {
        List<RawMessage> log = baseLog();
        set(log, 8, "поговорим про docker и контейнеры");

        var verdict = check(l1("заполнение 10", "заполнение 12", List.of("docker"), 12L), log);
        assertEquals(SpanValidator.IN_WINDOW, verdict.status());
        assertTrue(verdict.details().get(0).contains("message 8"), verdict.details().toString());
    }

    @Test
    void markerInsideTriggerMessageIsGiveaway() {
        List<RawMessage> log = baseLog();
        set(log, 10, "нужен docker или podman?");

        var verdict = check(l1("нужен docker или podman?", "заполнение 12",
                List.of("docker"), 12L), log);
        assertEquals(SpanValidator.IN_WINDOW, verdict.status());
        assertTrue(verdict.details().get(0).contains("message 10"), verdict.details().toString());
    }

    @Test
    void markerAtW0IsNotAViolation() {
        List<RawMessage> log = baseLog();
        set(log, 6, "раньше использовали docker compose");

        assertEquals(SpanValidator.OK, check(l1("заполнение 10", "заполнение 12",
                List.of("docker"), 12L), log).status());
    }

    @Test
    void mustNotInsideMustIsMarkerCollision() {
        // живой кейс project-v3: must «types-with-counts» содержит «counts» на границе
        // слов — любой ответ с must триггерит mustNot, pass недостижим
        var verdict = check(l1("заполнение 10", "заполнение 12",
                List.of("types-with-counts"), List.of("counts"), 12L), baseLog());
        assertEquals(SpanValidator.MARKER_COLLISION, verdict.status());
        assertTrue(verdict.details().get(0).contains("counts"), verdict.details().toString());
    }

    @Test
    void mustNotSupersetOfMustIsNotCollision() {
        // «typeId» внутри «resolvedTypeId» не на границе слов — коллизии нет
        assertEquals(SpanValidator.OK, check(l1("заполнение 10", "заполнение 12",
                List.of("typeId"), List.of("resolvedTypeId"), 12L), baseLog()).status());
    }

    @Test
    void windowMarkersRespectWordBoundaries() {
        List<RawMessage> log = baseLog();
        set(log, 8, "соберём через gradlew wrapper");
        set(log, 9, "Убеждусь, что тесты действительно выполнились");

        // латиница: «gradle» не матчится в «gradlew»; кириллица: «тест» не матчится
        // в «тесты» (ловля ревью 2026-09-14 — Unicode-границы слов)
        assertEquals(SpanValidator.OK, check(l1("заполнение 10", "заполнение 12",
                List.of("gradle", "тест"), 12L), log).status());
    }

    @Test
    void blankTriggerIsSpanMiss() {
        // пустой спан — не вырезка: contains("") всегда true, потому явный чек (ревью 1.2)
        var verdict = check(l1("   ", "заполнение 12", List.of(), 12L), baseLog());
        assertEquals(SpanValidator.SPAN_MISS, verdict.status());
    }

    @Test
    void nullTriggerIsSpanMiss() {
        var verdict = check(l1(null, "заполнение 12", List.of(), 12L), baseLog());
        assertEquals(SpanValidator.SPAN_MISS, verdict.status());
    }

    @Test
    void blankTruthWithAnchorIsSpanMiss() {
        var verdict = check(l1("заполнение 10", "", List.of(), 12L), baseLog());
        assertEquals(SpanValidator.SPAN_MISS, verdict.status());
        var nullTruth = check(l1("заполнение 10", null, List.of(), 12L), baseLog());
        assertEquals(SpanValidator.SPAN_MISS, nullTruth.status());
    }

    @Test
    void nullMarkerListsDoNotThrow() {
        // must/mustNot могут отсутствовать в jsonl — null-safe итерация (ревью 1.3)
        assertEquals(SpanValidator.OK, check(l1("заполнение 10", "заполнение 12", null, 12L),
                baseLog()).status());
        EvalPoint control = new EvalPoint("P", "C", "s", 10, "заполнение 10", null,
                null, null, null, null, null, null);
        assertEquals(SpanValidator.OK, check(control, baseLog()).status());
    }

    @Test
    void anchorNotAfterMIsCausality() {
        List<RawMessage> log = baseLog();
        set(log, 9, "реплика из прошлого");

        var verdict = check(l1("заполнение 10", "реплика из прошлого", List.of(), 9L), log);
        assertEquals(SpanValidator.CAUSALITY, verdict.status());
    }

    @Test
    void legacyPointWithoutAnchorIsUnanchored() {
        var verdict = check(l1("заполнение 10", null, List.of(), null), baseLog());
        assertEquals(SpanValidator.UNANCHORED, verdict.status());
    }

    @Test
    void controlPointChecksMustNotInWindow() {
        List<RawMessage> log = baseLog();
        set(log, 9, "поднимем kubernetes потом");

        var verdict = check(c("заполнение 10", List.of("kubernetes")), log);
        assertEquals(SpanValidator.IN_WINDOW, verdict.status());
    }

    @Test
    void controlPointWithCleanWindowPasses() {
        assertEquals(SpanValidator.OK,
                check(c("заполнение 10", List.of("kubernetes")), baseLog()).status());
    }

    @Test
    void lmePointSkipsSpanInvariants() {
        var verdict = SpanValidator.validate(
                l1("чего угодно", "чего угодно", List.of(), null), List.of(), WINDOW, true);
        assertEquals(SpanValidator.SKIP, verdict.status());
    }
}
