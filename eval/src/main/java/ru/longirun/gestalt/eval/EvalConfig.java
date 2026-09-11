package ru.longirun.gestalt.eval;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Конфиг eval: eval/local.properties (вне VCS) + env-переопределения (LLM_API_KEY). */
public record EvalConfig(
        String sourceDbUrl, String sourceDbUser, String sourceDbPassword,
        String sourceDayFrom, String sourceDayTo, String sourceFixture,
        String targetDbUrl, String targetDbUser, String targetDbPassword,
        String llmBaseUrl, String llmApiKey, String llmModel, String llmReasoningEffort,
        int batchMaxMessages, int batchMaxTokens,
        String portraitOwner, String portraitProject,
        String datasetFile) {

    public static EvalConfig load(Path propertiesFile) throws IOException {
        Properties props = new Properties();
        if (Files.exists(propertiesFile)) {
            try (InputStream in = Files.newInputStream(propertiesFile)) {
                props.load(in);
            }
        }
        String apiKey = props.getProperty("llm.api-key", "");
        if (System.getenv("LLM_API_KEY") != null) {
            apiKey = System.getenv("LLM_API_KEY");
        }
        // source.day — одиночный день (устаревший вариант); source.day-from/to — диапазон включительно
        String day = props.getProperty("source.day", "");
        String dayFrom = props.getProperty("source.day-from", day);
        String dayTo = props.getProperty("source.day-to", day);
        return new EvalConfig(
                props.getProperty("source.db.url", ""),
                props.getProperty("source.db.user", ""),
                props.getProperty("source.db.password", ""),
                dayFrom,
                dayTo,
                props.getProperty("source.fixture", ""),
                props.getProperty("target.db.url", "jdbc:postgresql://localhost:5433/gestalt_eval"),
                props.getProperty("target.db.user", ""),
                props.getProperty("target.db.password", ""),
                props.getProperty("llm.base-url", ""),
                apiKey,
                props.getProperty("llm.model", ""),
                props.getProperty("llm.reasoning-effort", ""),
                Integer.parseInt(props.getProperty("batch.max-messages", "20")),
                Integer.parseInt(props.getProperty("batch.max-tokens", "2000")),
                props.getProperty("portrait.owner", ""),
                props.getProperty("portrait.project", ""),
                props.getProperty("dataset.file", "dataset/points.jsonl"));
    }
}
