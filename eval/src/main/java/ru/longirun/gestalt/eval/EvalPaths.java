package ru.longirun.gestalt.eval;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Пути модуля eval: cwd зависит от способа запуска (gradle-run — eval/, ручной —
 * корень репо, тесты из IDE — eval/), поэтому все пути репо/модуля резолвятся
 * относительно нескольких корней. Используется и runner'ом, и viewer-тестом (план 31 §2.7).
 */
final class EvalPaths {

    private EvalPaths() {
    }

    /** Директория модуля eval. */
    static Path evalDir() {
        Path cwd = Path.of("").toAbsolutePath();
        if (Files.exists(cwd.resolve("settings.gradle"))) {
            return cwd;
        }
        return cwd.resolve("eval");
    }

    /**
     * Пути конфига репо-относительны; запуск возможен и из корня репо, и из eval/
     * (у gradle-run cwd = eval/). Проверяем как есть, без префикса, от корня репо и с префиксом.
     */
    static Path resolve(String repoRelativePath) {
        Path path = Path.of(repoRelativePath);
        if (Files.exists(path)) {
            return path;
        }
        if (repoRelativePath.startsWith("eval/")) {
            Path stripped = Path.of(repoRelativePath.substring("eval/".length()));
            if (Files.exists(stripped)) {
                return stripped;
            }
        }
        Path fromEvalCwd = Path.of("..").resolve(repoRelativePath).normalize();
        if (Files.exists(fromEvalCwd)) {
            return fromEvalCwd;
        }
        Path withPrefix = Path.of("eval").resolve(repoRelativePath);
        if (Files.exists(withPrefix)) {
            return withPrefix;
        }
        return path;
    }
}
