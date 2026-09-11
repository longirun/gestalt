package ru.longirun.gestalt.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Конфиг eval из properties-файла: дефолты и чтение значений.
 * llm.api-key не ассертится: env LLM_API_KEY перекрывает ключ и в тестовом процессе.
 */
class EvalConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultsWhenFileMissing() throws IOException {
        EvalConfig config = EvalConfig.load(tempDir.resolve("missing.properties"));

        assertEquals("jdbc:postgresql://localhost:5433/gestalt_eval", config.targetDbUrl());
        assertEquals("", config.targetDbUser());
        assertEquals("", config.targetDbPassword());
        assertEquals(20, config.batchMaxMessages());
        assertEquals(2000, config.batchMaxTokens());
        assertEquals("dataset/points.jsonl", config.datasetFile());
        assertEquals("", config.sourceDbUrl());
        assertEquals("", config.sourceDay());
        assertEquals("", config.sourceFixture());
        assertEquals("", config.llmBaseUrl());
        assertEquals("", config.llmModel());
        assertEquals("", config.llmReasoningEffort());
        assertEquals("", config.portraitOwner());
        assertEquals("", config.portraitProject());
    }

    @Test
    void readsValuesFromPropertiesFile() throws IOException {
        Path file = tempDir.resolve("local.properties");
        Files.writeString(file, String.join("\n",
                "source.db.url=jdbc:postgresql://localhost:5433/honcho_memory",
                "source.db.user=gestalt_ro",
                "source.db.password=ro-secret",
                "source.day=2026-08-01",
                "source.fixture=fixture/log.jsonl",
                "target.db.url=jdbc:postgresql://localhost:5433/gestalt_eval_test",
                "target.db.user=eval_user",
                "target.db.password=eval_pass",
                "llm.base-url=https://router.example/api/v1",
                "llm.api-key=file-key",
                "llm.model=test-model",
                "llm.reasoning-effort=medium",
                "batch.max-messages=7",
                "batch.max-tokens=1500",
                "portrait.owner=user:tester",
                "portrait.project=project:gestalt",
                "dataset.file=data/points.test.jsonl"));
        EvalConfig config = EvalConfig.load(file);

        assertEquals("jdbc:postgresql://localhost:5433/honcho_memory", config.sourceDbUrl());
        assertEquals("gestalt_ro", config.sourceDbUser());
        assertEquals("ro-secret", config.sourceDbPassword());
        assertEquals("2026-08-01", config.sourceDay());
        assertEquals("fixture/log.jsonl", config.sourceFixture());
        assertEquals("jdbc:postgresql://localhost:5433/gestalt_eval_test", config.targetDbUrl());
        assertEquals("eval_user", config.targetDbUser());
        assertEquals("eval_pass", config.targetDbPassword());
        assertEquals("https://router.example/api/v1", config.llmBaseUrl());
        assertEquals("test-model", config.llmModel());
        assertEquals("medium", config.llmReasoningEffort());
        assertEquals(7, config.batchMaxMessages());
        assertEquals(1500, config.batchMaxTokens());
        assertEquals("user:tester", config.portraitOwner());
        assertEquals("project:gestalt", config.portraitProject());
        assertEquals("data/points.test.jsonl", config.datasetFile());
    }

    @Test
    void partialOverridesKeepOtherDefaults() throws IOException {
        Path file = tempDir.resolve("partial.properties");
        Files.writeString(file, String.join("\n",
                "target.db.user=custom_user",
                "batch.max-messages=3"));
        EvalConfig config = EvalConfig.load(file);

        assertEquals("custom_user", config.targetDbUser());
        assertEquals(3, config.batchMaxMessages());
        assertEquals("jdbc:postgresql://localhost:5433/gestalt_eval", config.targetDbUrl());
        assertEquals(2000, config.batchMaxTokens());
        assertEquals("dataset/points.jsonl", config.datasetFile());
    }
}
