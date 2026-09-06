package ru.longirun.gestalt.spike;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Конфиг спайка: spike/local.properties (вне VCS) + env-переопределения (LLM_API_KEY). */
public record SpikeConfig(
        String sourceDbUrl, String sourceDbUser, String sourceDbPassword,
        String targetDbUrl, String targetDbUser, String targetDbPassword,
        String llmBaseUrl, String llmApiKey, String llmModel,
        int batchMaxMessages, int batchMaxTokens,
        String sourceSession, String sourceDay) {

    public static SpikeConfig load(Path propertiesFile) throws IOException {
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
        return new SpikeConfig(
                props.getProperty("source.db.url", ""),
                props.getProperty("source.db.user", ""),
                props.getProperty("source.db.password", ""),
                props.getProperty("target.db.url", ""),
                props.getProperty("target.db.user", ""),
                props.getProperty("target.db.password", ""),
                props.getProperty("llm.base-url", ""),
                apiKey,
                props.getProperty("llm.model", ""),
                Integer.parseInt(props.getProperty("extractor.batch.max-messages", "20")),
                Integer.parseInt(props.getProperty("extractor.batch.max-tokens", "2000")),
                props.getProperty("source.session", ""),
                props.getProperty("source.day", ""));
    }
}
