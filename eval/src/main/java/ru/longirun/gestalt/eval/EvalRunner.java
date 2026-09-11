package ru.longirun.gestalt.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CLI eval-каркаса (ADR 28 §3.6): replay → плечи A/B → оракулы → lift → отчёт.
 * Каркас без реализации: тела шагов — заглушки, поглощение кода спайка (§3.1) и
 * статистика go/no-go (§4) — следующие стадии.
 */
public final class EvalRunner {

    public static void main(String[] args) throws Exception {
        String step = args.length > 0 ? args[0] : "all";
        EvalConfig config = EvalConfig.load(resolve("eval/local.properties"));

        switch (step) {
            case "replay" -> runReplay(config);
            case "arms" -> runArms(config);
            case "oracles" -> runOracles(config);
            case "report" -> runReport(config);
            case "all" -> runAll(config);
            default -> throw new IllegalArgumentException("unknown step: " + step);
        }
    }

    /** Replay живого лога до позиции T каждой точки (чекпоинты, кэш инжеста — Р36). */
    private static void runReplay(EvalConfig config) {
        throw new UnsupportedOperationException("not implemented yet: replay");
    }

    /** Оба плеча на триггерах: A — с инъекцией PortraitSnapshot, B — контроль без портрета (заморозка кэша). */
    private static void runArms(EvalConfig config) {
        throw new UnsupportedOperationException("not implemented yet: arms");
    }

    /** Машинные проверки ответов: must/must_not, edit-rate; C-точки — отдельный булев leak-гейт. */
    private static void runOracles(EvalConfig config) {
        throw new UnsupportedOperationException("not implemented yet: oracles");
    }

    /** Lift на решённых парах + McNemar exact (α = 0.05), конструируемость; вердикты асимметричны (§4). */
    private static void runReport(EvalConfig config) {
        throw new UnsupportedOperationException("not implemented yet: report");
    }

    private static void runAll(EvalConfig config) throws IOException {
        Path points = resolve("eval/dataset/points.jsonl");
        long count = Files.exists(points)
                ? Files.lines(points).filter(line -> !line.isBlank()).count()
                : 0;
        System.out.printf("[EVAL] Skeleton. %d point(s) in %s. Steps replay/arms/oracles/report: not implemented yet.%n",
                count, points);
    }

    private static Path resolve(String repoRelativePath) {
        Path path = Path.of(repoRelativePath);
        if (Files.exists(path)) {
            return path;
        }
        return Path.of(repoRelativePath.replaceFirst("^eval/", ""));
    }
}
