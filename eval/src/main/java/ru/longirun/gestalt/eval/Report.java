package ru.longirun.gestalt.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import ru.longirun.gestalt.eval.store.ExperimentStore;
import ru.longirun.gestalt.eval.store.ResultStore;
import ru.longirun.gestalt.eval.store.RunStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Стадия E5 — сводный отчёт (ADR 28 §3.5): lift = P(pass|A) − P(pass|B) на машинных
 * оракулах (нижняя граница: морфологию и парафразы закрывает LLM-судья в полном ярусе),
 * значимость — точный критерий МакНемара (двусторонний биномиальный на рассогласованных
 * парах). C-точки с leak в lift не входят (veto Р36). Выход: out/report.md + консоль.
 */
public final class Report {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Сводка по прогону; fails — список точек «оба провалились» (материал для судьи). */
    public record Stats(int n, int aPass, int bPass, int aOnly, int bOnly,
                        int bothPass, int bothFail, int leaks,
                        List<Oracles.PointVerdict> bothFailPoints) {
    }

    private Report() {
    }

    static Stats stats(List<Oracles.PointVerdict> verdicts) {
        int aOnly = 0;
        int bOnly = 0;
        int bothPass = 0;
        List<Oracles.PointVerdict> fails = new ArrayList<>();
        int leaks = 0;
        for (Oracles.PointVerdict v : verdicts) {
            boolean a = v.a().pass();
            boolean b = v.b().pass();
            if (v.leak()) {
                leaks++;
                continue;
            }
            if (a && !b) {
                aOnly++;
            } else if (b && !a) {
                bOnly++;
            } else if (a) {
                bothPass++;
            } else {
                fails.add(v);
            }
        }
        int n = aOnly + bOnly + bothPass + fails.size();
        return new Stats(n, aOnly + bothPass, bOnly + bothPass, aOnly, bOnly,
                bothPass, fails.size(), leaks, fails);
    }

    /**
     * Точный двусторонний МакНемар: p = 2·P(X ≤ min(b,c)), X ~ Bin(b+c, ½), где b — пары
     * «только B», c — «только A». C(n,k) считается итеративно (double достаточно: n ≤ сотен).
     */
    static double mcnemarExact(int b, int c) {
        int n = b + c;
        if (n == 0) {
            return 1.0;
        }
        int m = Math.min(b, c);
        double tail = 0;
        double binom = 1; // C(n, 0)
        for (int k = 0; k <= m; k++) {
            if (k > 0) {
                binom = binom * (n - k + 1) / k;
            }
            tail += binom;
        }
        return Math.min(1.0, 2 * tail / Math.pow(2, n));
    }

    /**
     * Читает вердикты эксперимента из gestalt_eval (§7 — PG источник истины), пишет
     * out/report.md (генерируемый артефакт, §7.5) и сводку в консоль. Прогон регистрируется
     * в runs (writer-проход: история отчётов). Слаг не задан → единственный активный
     * эксперимент; иначе ошибка со списком.
     */
    public static void run(EvalConfig config, String experimentSlug) throws Exception {
        List<Oracles.PointVerdict> verdicts;
        String experiment;
        try (Connection conn = DriverManager.getConnection(
                config.targetDbUrl(), config.targetDbUser(), config.targetDbPassword())) {
            ExperimentStore experiments = new ExperimentStore(conn);
            experiment = Experiments.resolveActive(experiments, experimentSlug);
            RunStore runs = new RunStore(conn);
            ResultStore results = new ResultStore(conn);
            int stale = runs.interruptStale(experiment, "report");
            if (stale > 0) {
                System.out.printf("[REPORT] %d застрявших running-прогонов report помечены interrupted (§7.3)%n", stale);
            }
            long runId = runs.start(experiment, "report", "сводный отчёт из вердиктов PG");
            try {
                verdicts = new ArrayList<>();
                for (ResultStore.VerdictRow row : results.verdicts(experiment)) {
                    verdicts.add(MAPPER.readValue(row.payloadJson(), Oracles.PointVerdict.class));
                }
                runs.finish(runId, "done");
            } catch (Exception e) {
                runs.finish(runId, "failed");
                throw e;
            }
        }
        if (verdicts.isEmpty()) {
            throw new IllegalStateException("эксперимент '%s': нет вердиктов в gestalt_eval — прогони oracles или migrate"
                    .formatted(experiment));
        }

        Stats s = stats(verdicts);
        // n=0 (тотальный leak-veto) не рождает отчёт: проценты дали бы NaN,
        // а нули ложно читались бы как «обе руки провалили всё»
        if (s.n() == 0) {
            throw new IllegalStateException(("эксперимент '%s': все %d точек ушли в C-leak veto (veto Р36) — "
                    + "выборка для lift пуста, проверь C-точки и их слепки").formatted(experiment, s.leaks()));
        }
        double p = mcnemarExact(s.bOnly(), s.aOnly());
        double lift = s.n() == 0 ? 0 : (double) (s.aPass() - s.bPass()) / s.n();
        String verdict = (lift > 0 && p < 0.05) ? "lift > 0 статистически значим (α=0.05)"
                : "значимость не достигнута";

        StringBuilder md = new StringBuilder();
        md.append("# Gestalt eval — E5 report\n\n");
        md.append(String.format("Эксперимент: `%s` (вердикты из gestalt_eval, §7).%n", experiment));
        md.append(String.format("Машинный оракул (нижняя граница; парафразы/морфология — ярус судьи).%n%n"));
        md.append("| метрика | значение |\n|---|---|\n");
        md.append(String.format("| точек (без leak-veto) | %d |%n", s.n()));
        md.append(String.format("| pass A (память) | %d (%.0f%%) |%n", s.aPass(), 100.0 * s.aPass() / s.n()));
        md.append(String.format("| pass B (контроль) | %d (%.0f%%) |%n", s.bPass(), 100.0 * s.bPass() / s.n()));
        md.append(String.format("| **lift** | **%+.0f pp** |%n", 100.0 * lift));
        md.append(String.format("| пары только A / только B | %d / %d |%n", s.aOnly(), s.bOnly()));
        md.append(String.format("| оба pass / оба fail | %d / %d |%n", s.bothPass(), s.bothFail()));
        md.append(String.format("| C-leak (veto) | %d |%n", s.leaks()));
        md.append(String.format("%nМакНемар exact (рассогласованных пар %d): p = %.2g — %s%n%n",
                s.aOnly() + s.bOnly(), p, verdict));

        if (!s.bothFailPoints().isEmpty()) {
            md.append("## Оба плеча провалились (материал для судьи / ручного разбора)\n\n");
            for (Oracles.PointVerdict v : s.bothFailPoints()) {
                md.append(String.format("- `%s`: missed %s%n", v.pointId(), v.a().missedMust()));
            }
        }

        Path outFile = EvalPaths.evalDir().resolve("out/report.md");
        Files.writeString(outFile, md.toString());

        System.out.printf("[REPORT] n=%d (leak-veto %d) | A %d/%d (%.0f%%) vs B %d (%.0f%%) | lift %+.0fpp | McNemar p=%.2g — %s%n",
                s.n(), s.leaks(), s.aPass(), s.n(), 100.0 * s.aPass() / s.n(),
                s.bPass(), 100.0 * s.bPass() / s.n(), 100.0 * lift, p, verdict);
        System.out.printf("[REPORT] both-pass %d, both-fail %d (список в отчёте) -> %s%n",
                s.bothPass(), s.bothFail(), outFile.normalize());
    }
}
