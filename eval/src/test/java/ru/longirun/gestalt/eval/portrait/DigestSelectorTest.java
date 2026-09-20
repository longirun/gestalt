package ru.longirun.gestalt.eval.portrait;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Read-time селекция дайджеста (E7): глобальный top-K по косинусу, секционная
 * структура вывода, tie-break сим→reinforcement→createdAt, громкий отказ на дырах
 * в эмбеддингах. Векторы фейковые — HTTP/PG-границы не тестируются (конвенция).
 */
class DigestSelectorTest {

    private static final String SNAPSHOT = """
            {"critical":[
               {"id":"11111111-1111-1111-1111-111111111111","statement":"user drinks coffee","reinforcement_count":3},
               {"id":"22222222-2222-2222-2222-222222222222","statement":"user uses vpn","reinforcement_count":1}],
             "constructs":[
               {"id":"33333333-3333-3333-3333-333333333333","statement":"user likes tea","reinforcement_count":2}],
             "preferences":[
               {"id":"44444444-4444-4444-4444-444444444444","subject":"user","predicate":"editor","object":"vim"}]}
            """;

    private static final UUID COFFEE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VPN = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TEA = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID VIM = UUID.fromString("44444444-4444-4444-4444-444444444444");

    /** Триггер [1,0]: близость к кофе максимальна, чай средний, vpn слабый, vim антиден. */
    private static final double[] TRIGGER = {1.0, 0.0};

    private static Map<UUID, double[]> vectors() {
        return Map.of(
                COFFEE, new double[]{0.9, 0.1},
                VPN, new double[]{0.1, 0.9},
                TEA, new double[]{0.5, 0.5},
                VIM, new double[]{-1.0, 0.0});
    }

    @Test
    void topKIsGlobalAcrossSectionsAndKeepsSectionStructure() {
        String digest = DigestSelector.render(SNAPSHOT, TRIGGER, vectors(), Map.of(), 2);

        assertTrue(digest.contains("[critical]"));
        assertTrue(digest.contains("- user drinks coffee"));
        assertTrue(digest.contains("[constructs]"));
        assertTrue(digest.contains("- user likes tea"));
        assertFalse(digest.contains("user uses vpn"), "слабый факт зарезан глобальным top-K");
        assertFalse(digest.contains("[preferences]"), "опустевшая секция не печатается");
        assertFalse(digest.contains("vim"), "антиден не попадает в отбор");
    }

    @Test
    void topKBeyondFactCountKeepsEverything() {
        String digest = DigestSelector.render(SNAPSHOT, TRIGGER, vectors(), Map.of(), 100);

        assertTrue(digest.contains("user uses vpn"));
        assertTrue(digest.contains("user · editor · vim"));
    }

    @Test
    void fullModeWithoutTriggerIgnoresRanking() {
        String digest = DigestSelector.render(SNAPSHOT, null, Map.of(), Map.of(), 0);

        assertEquals(ArmsFullDigestReference.digest(), digest,
                "без селекции — полный дайджест в порядке слепка (режим E6)");
    }

    @Test
    void emptySnapshotYieldsEmptyDigest() {
        assertEquals("", DigestSelector.render(null, TRIGGER, vectors(), Map.of(), 5));
        assertEquals("", DigestSelector.render("{}", TRIGGER, vectors(), Map.of(), 5));
        assertEquals("", DigestSelector.render("{\"critical\":[]}", TRIGGER, vectors(), Map.of(), 5));
    }

    @Test
    void tieBreakReinforcementThenCreatedAt() {
        // все факты коллинеарны триггеру (sim = 1): решает reinforcement, затем createdAt ASC
        String snapshot = """
                {"critical":[
                   {"id":"aaaaaaaa-0000-0000-0000-000000000001","statement":"first","reinforcement_count":1},
                   {"id":"aaaaaaaa-0000-0000-0000-000000000002","statement":"second","reinforcement_count":5}],
                 "constructs":[
                   {"id":"aaaaaaaa-0000-0000-0000-000000000003","statement":"third","reinforcement_count":5},
                   {"id":"aaaaaaaa-0000-0000-0000-000000000004","statement":"fourth","reinforcement_count":5}]}
                """;
        UUID first = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
        UUID third = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003");
        UUID fourth = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000004");
        Map<UUID, double[]> vectors = Map.of(
                first, new double[]{1, 0}, second, new double[]{2, 0},
                third, new double[]{3, 0}, fourth, new double[]{4, 0});
        Map<UUID, OffsetDateTime> createdAt = Map.of(
                second, OffsetDateTime.parse("2026-06-01T00:00:00Z"),
                third, OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                fourth, OffsetDateTime.parse("2026-03-01T00:00:00Z"));

        String digest = DigestSelector.render(snapshot, TRIGGER, vectors, createdAt, 2);

        int posThird = digest.indexOf("- third");
        int posFourth = digest.indexOf("- fourth");
        assertTrue(posThird >= 0 && posFourth >= 0, "top-2 — факты с reinforcement 5");
        assertTrue(posThird < posFourth, "при равных sim/rein раньше более старый факт (createdAt ASC)");
        assertFalse(digest.contains("- second"), "проигравший tie-break по createdAt зарезан top-K");
        assertFalse(digest.contains("- first"), "проигравший tie-break по reinforcement зарезан top-K");
    }

    @Test
    void factWithoutVectorFailsLoudly() {
        Map<UUID, double[]> incomplete = Map.of(COFFEE, new double[]{1, 0});

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> DigestSelector.render(SNAPSHOT, TRIGGER, incomplete, Map.of(), 2));
        assertTrue(e.getMessage().contains("embed"), "подсказка запустить backfill: " + e.getMessage());
    }

    @Test
    void topKBelowOneRejectedInSelectionMode() {
        assertThrows(IllegalArgumentException.class,
                () -> DigestSelector.render(SNAPSHOT, TRIGGER, vectors(), Map.of(), 0));
    }

    @Test
    void factIdsFollowSectionOrder() {
        assertEquals(java.util.List.of(COFFEE, VPN, TEA, VIM), DigestSelector.factIds(SNAPSHOT));
    }

    @Test
    void cosineBasics() {
        assertEquals(1.0, DigestSelector.cosine(new double[]{1, 0}, new double[]{2, 0}), 1e-9);
        assertEquals(0.0, DigestSelector.cosine(new double[]{1, 0}, new double[]{0, 1}), 1e-9);
        assertEquals(0.7071, DigestSelector.cosine(new double[]{1, 0}, new double[]{1, 1}), 1e-4);
        assertThrows(IllegalArgumentException.class,
                () -> DigestSelector.cosine(new double[]{1, 0}, new double[]{1, 0, 0}));
        assertThrows(IllegalArgumentException.class,
                () -> DigestSelector.cosine(new double[]{0, 0}, new double[]{1, 0}));
    }

    @Test
    void parseVectorLiterals() {
        double[] v = DigestSelector.parseVector("[0.5, -0.25, 1e-3]");
        assertEquals(3, v.length);
        assertEquals(0.5, v[0], 1e-12);
        assertEquals(-0.25, v[1], 1e-12);
        assertNull(DigestSelector.parseVector(null), "NULL-колонка = нет эмбеддинга");
        assertThrows(IllegalArgumentException.class, () -> DigestSelector.parseVector("1,2,3"));
    }

    /** Эталон полного дайджеста: руками выписанный ожидаемый вывод режима E6. */
    private static final class ArmsFullDigestReference {
        static String digest() {
            return """
                    [critical]
                    - user drinks coffee
                    - user uses vpn
                    [constructs]
                    - user likes tea
                    [preferences]
                    - user · editor · vim""".stripIndent();
        }
    }
}
