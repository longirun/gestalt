package ru.longirun.gestalt.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E5: статистики отчёта (счёты по вердиктам, leak-veto исключается из n) и точный
 * МакНемар (двусторонний биномиальный на рассогласованных парах).
 */
class ReportTest {

    private static Oracles.PointVerdict point(String id, boolean a, boolean b) {
        return new Oracles.PointVerdict(id, "L1",
                new Oracles.ArmVerdict(a, a ? List.of() : List.of("x"), List.of()),
                new Oracles.ArmVerdict(b, b ? List.of() : List.of("x"), List.of()),
                false, List.of());
    }

    @Test
    void statsCountsAndLiftVetoExcluded() {
        List<Oracles.PointVerdict> vs = List.of(
                point("aOnly1", true, false),
                point("aOnly2", true, false),
                point("bOnly", false, true),
                point("bothPass", true, true),
                point("bothFail", false, false),
                new Oracles.PointVerdict("leaked", "C",
                        new Oracles.ArmVerdict(false, List.of("x"), List.of("SECRET")),
                        new Oracles.ArmVerdict(false, List.of("x"), List.of()), true, List.of("SECRET")));

        Report.Stats s = Report.stats(vs);

        assertEquals(5, s.n(), "leak-veto не входит в n");
        assertEquals(3, s.aPass());
        assertEquals(2, s.bPass());
        assertEquals(2, s.aOnly());
        assertEquals(1, s.bOnly());
        assertEquals(1, s.bothPass());
        assertEquals(1, s.bothFail());
        assertEquals(1, s.leaks());
        assertEquals(List.of("bothFail"), s.bothFailPoints().stream().map(Oracles.PointVerdict::pointId).toList());
    }

    @Test
    void allLeakVetoLeavesEmptySample() {
        Oracles.PointVerdict leaked = new Oracles.PointVerdict("leaked", "C",
                new Oracles.ArmVerdict(false, List.of("x"), List.of("SECRET")),
                new Oracles.ArmVerdict(false, List.of("x"), List.of()), true, List.of("SECRET"));

        Report.Stats s = Report.stats(List.of(leaked, leaked));

        // предусловие отказа в run(): проценты при n=0 дали бы NaN
        assertEquals(0, s.n());
        assertEquals(2, s.leaks());
    }

    @Test
    void mcnemarExactMatchesBinomial() {
        assertEquals(1.0, Report.mcnemarExact(0, 0));
        // 2·2^-30 — глубокая значимость при полном разгроме 0:30
        assertEquals(2.0 / Math.pow(2, 30), Report.mcnemarExact(0, 30), 1e-15);
        // симметричная выборка — p = 1
        assertEquals(1.0, Report.mcnemarExact(5, 5), 1e-12);
        // n=10, m=1: 2·(C(10,0)+C(10,1))/2^10 = 22/1024
        assertEquals(22.0 / 1024.0, Report.mcnemarExact(1, 9), 1e-12);
        // p не вылезает за 1 даже на хвостах
        assertTrue(Report.mcnemarExact(2, 8) <= 1.0);
    }
}
