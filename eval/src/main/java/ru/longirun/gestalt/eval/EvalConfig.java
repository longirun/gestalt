package ru.longirun.gestalt.eval;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Конфиг eval: eval/local.properties (вне VCS) + env-переопределения (LLM_API_KEY). */
public record EvalConfig(
        String sourceDbUrl, String sourceDbUser, String sourceDbPassword,
        String llmBaseUrl, String llmApiKey, String llmModel) {

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
        return new EvalConfig(
                props.getProperty("source.db.url", ""),
                props.getProperty("source.db.user", ""),
                props.getProperty("source.db.password", ""),
                props.getProperty("llm.base-url", ""),
                apiKey,
                props.getProperty("llm.model", ""));
    }
}
