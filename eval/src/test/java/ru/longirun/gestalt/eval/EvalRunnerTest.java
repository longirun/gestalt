package ru.longirun.gestalt.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Маршрутизация owner/project точки (E2-PC): LME-вопрос — изолированный микромир
 * с синтетическим владельцем, иначе — из конфига.
 */
class EvalRunnerTest {

    @TempDir
    Path tempDir;

    private EvalConfig config(String lmeFile) throws Exception {
        Path file = tempDir.resolve("local.properties");
        Files.writeString(file, String.join("\n",
                "portrait.owner=user:alice",
                "portrait.project=project:sample-app",
                lmeFile == null ? "" : "source.lme.file=" + lmeFile));
        return EvalConfig.load(file);
    }

    @Test
    void lmeSessionGetsSyntheticOwnerAndProject() throws Exception {
        EvalConfig config = config("data/lme/longmemeval_oracle.json");

        assertEquals("lme-q_alpha", EvalRunner.portraitProject(config, "lme-q_alpha"));
        assertEquals("user:lme-q_alpha", EvalRunner.portraitOwner(config, "lme-q_alpha"));
    }

    @Test
    void liveSessionKeepsConfigOwnerAndProject() throws Exception {
        EvalConfig config = config("data/lme/longmemeval_oracle.json");

        assertEquals("project:sample-app", EvalRunner.portraitProject(config, "session-live-01"));
        assertEquals("user:alice", EvalRunner.portraitOwner(config, "session-live-01"));
    }

    @Test
    void lmePrefixWithoutLmeFileIsLive() throws Exception {
        EvalConfig config = config(null);

        assertEquals("project:sample-app", EvalRunner.portraitProject(config, "lme-q_alpha"));
        assertEquals("user:alice", EvalRunner.portraitOwner(config, "lme-q_alpha"));
    }
}
