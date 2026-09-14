package ru.longirun.gestalt.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Оракулы E4: матчинг must/mustNot с границами слов (урок E2-PC: «18» не должно
 * совпадать с «18th»), ё→е, вердикты плеч, C-leak.
 */
class OraclesTest {

    @Test
    void numericMarkerRespectsWordBoundaries() {
        assertTrue(Oracles.matches("about 18 minutes every day", "18"));
        assertFalse(Oracles.matches("their 180 hours", "18"));
        assertFalse(Oracles.matches("their 2018 report", "18"));
        // «18th» ≠ «18» — морфологию сознательно не закрываем (языко-зависимо, ярус судьи)
        assertFalse(Oracles.matches("on your 18th birthday", "18"));
    }

    @Test
    void cyrillicMarkersRespectWordBoundaries() {
        // ловля ревью 2026-09-14: ASCII-only [A-Za-z0-9] считал кириллицу разделителем —
        // «тест» ложно матчился в «тесты»/«тестирование»/«протест»; теперь [\p{L}\p{N}]
        assertTrue(Oracles.matches("запущу тесты отдельно", "тесты"));
        assertFalse(Oracles.matches("Убеждусь, что тесты действительно выполнились", "тест"));
        assertFalse(Oracles.matches("нужно тестирование вручную", "тест"));
        assertFalse(Oracles.matches("это протест против конфигурации", "тест"));
        assertFalse(Oracles.matches("зацикливание сборки", "цикл"));
        assertTrue(Oracles.matches("повтори цикл ещё раз", "цикл"));
    }

    @Test
    void matchingIsCaseInsensitiveAndNormalizesYo() {
        assertTrue(Oracles.matches("a Golden Retriever puppy", "golden retriever"));
        assertTrue(Oracles.matches("Всё готово", "всё"), "ё маркера матчится против е в ответе");
        assertTrue(Oracles.matches("Лед и пламень", "лёд"), "ё в ответе матчится против е маркера");
    }

    @Test
    void markersWithPunctuationMatch() {
        assertTrue(Oracles.matches("The discount was 10% of $800", "10%"));
        assertTrue(Oracles.matches("It cost $800 total", "$800"));
        assertFalse(Oracles.matches("The discount was 100 dollars", "10%"));
    }

    @Test
    void markdownEmphasisDoesNotBreakMatching() {
        // урок LME-3d86fd0a: жирные звёздочки вокруг ответа не должны рвать подстроку
        assertTrue(Oracles.matches("You met Sophia at a **coffee shop in the city**.",
                "a coffee shop in the city"));
    }

    @Test
    void morphologicalAndParaphraseMissesAreJudgeTier() {
        // документируем семантику smoke-яруса: всё ниже — промах для машинного оракула
        assertFalse(Oracles.matches("at a downtown sports store", "the sports store downtown"));
        assertFalse(Oracles.matches("more than a year", "over a year"));
        assertFalse(Oracles.matches("Your sister gave you the mixer", "my sister"));
    }

    @Test
    void passRequiresAllMustAndNoMustNot() {
        Oracles.ArmVerdict pass = Oracles.verdict(
                "You graduated with a degree in Business Administration",
                List.of("Business Administration"), List.of());
        Oracles.ArmVerdict miss = Oracles.verdict(
                "I don't have any information about your degree",
                List.of("Business Administration"), List.of());
        Oracles.ArmVerdict leak = Oracles.verdict(
                "Business Administration, and secretly DEBUG",
                List.of("Business Administration"), List.of("DEBUG"));

        assertTrue(pass.pass());
        assertFalse(miss.pass());
        assertEquals(List.of("Business Administration"), miss.missedMust());
        assertFalse(leak.pass());
        assertEquals(List.of("DEBUG"), leak.matchedMustNot());
    }

    @Test
    void paraphraseIsMissForMachineOracle() {
        // строгая семантика smoke-яруса: парафраз = fail, материал для ручного разбора
        // (missedMust виден в viewer) и аргумент за LLM-судью в полном ярусе
        Oracles.ArmVerdict v = Oracles.verdict(
                "You studied business at uni", List.of("Business Administration"), List.of());

        assertFalse(v.pass());
        assertEquals(List.of("Business Administration"), v.missedMust());
    }

    @Test
    void emptyAnswerFailsGracefully() {
        Oracles.ArmVerdict v = Oracles.verdict("", List.of("Business Administration"), List.of());

        assertFalse(v.pass());
        assertEquals(1, v.missedMust().size());
    }
}
