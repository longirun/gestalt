package ru.longirun.gestalt.eval;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Конфиг eval: eval/local.properties (вне VCS) + env-переопределения (LLM_API_KEY).
 * Поля llm.answer.* резолвнуты при load(): blank base-url/api-key/model наследуют
 * экстракционные llm.*, reasoning-effort независим (плечо ответа управляется явно).
 */
public record EvalConfig(
        String sourceDbUrl, String sourceDbUser, String sourceDbPassword,
        String sourceDayFrom, String sourceDayTo, String sourceFixture, String sourceLmeFile,
        String targetDbUrl, String targetDbUser, String targetDbPassword,
        String llmBaseUrl, String llmApiKey, String llmModel, String llmReasoningEffort,
        String llmAnswerBaseUrl, String llmAnswerApiKey, String llmAnswerModel, String llmAnswerReasoningEffort,
        int batchMaxMessages, int batchMaxTokens,
        int windowSize,
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
        String llmBaseUrl = props.getProperty("llm.base-url", "");
        String llmModel = props.getProperty("llm.model", "");
        // отвечающая модель плеч (E3): blank наследует экстракционную llm.* — единая
        // семантика (отсутствует ключ или пустое значение) для рантайма и configSnapshot
        String answerBaseUrl = props.getProperty("llm.answer.base-url", "");
        if (answerBaseUrl.isBlank()) {
            answerBaseUrl = llmBaseUrl;
        }
        String answerApiKey = props.getProperty("llm.answer.api-key", "");
        if (answerApiKey.isBlank()) {
            answerApiKey = apiKey;
        }
        String answerModel = props.getProperty("llm.answer.model", "");
        if (answerModel.isBlank()) {
            answerModel = llmModel;
        }
        return new EvalConfig(
                props.getProperty("source.db.url", ""),
                props.getProperty("source.db.user", ""),
                props.getProperty("source.db.password", ""),
                dayFrom,
                dayTo,
                props.getProperty("source.fixture", ""),
                props.getProperty("source.lme.file", ""),
                props.getProperty("target.db.url", "jdbc:postgresql://localhost:5433/gestalt_eval"),
                props.getProperty("target.db.user", ""),
                props.getProperty("target.db.password", ""),
                llmBaseUrl,
                apiKey,
                llmModel,
                props.getProperty("llm.reasoning-effort", ""),
                answerBaseUrl,
                answerApiKey,
                answerModel,
                props.getProperty("llm.answer.reasoning-effort", ""),
                Integer.parseInt(props.getProperty("batch.max-messages", "20")),
                Integer.parseInt(props.getProperty("batch.max-tokens", "2000")),
                Integer.parseInt(props.getProperty("window.size", "5")),
                props.getProperty("portrait.owner", ""),
                props.getProperty("portrait.project", ""),
                props.getProperty("dataset.file", "dataset/points.jsonl"));
    }
}
