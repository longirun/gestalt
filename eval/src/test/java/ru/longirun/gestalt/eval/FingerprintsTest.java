package ru.longirun.gestalt.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Флаги совместимости прибора (§7.2): стабильность на одном конфиге, чувствительность
 * к составным частям (модель/промпт-вход/батчинг/окно), идентичность источника по датасету.
 */
class FingerprintsTest {

    private static EvalConfig config(String lmeFile, String model, String answerModel,
                                     int batchMessages, int window) {
        return new EvalConfig(
                "jdbc:postgresql://localhost:5433/honcho_memory", "u", "p",
                "2026-06-15", "2026-08-11", "", lmeFile,
                "jdbc:postgresql://localhost:5433/gestalt_eval", "u", "p",
                "https://llm", "key", model, "low",
                "https://llm", "key", answerModel, "low",
                batchMessages, 2000, window, "user:t", "project:t", "dataset/points.jsonl");
    }

    @Test
    void stableForSameConfigAndDataset() throws Exception {
        List<EvalPoint> points = List.of(new EvalPoint("p1", "L1", "lme-abc", 10,
                "t", "tr", List.of(), List.of(), null, null, null, null));
        EvalConfig config = config("src/test/resources/lme/tiny.json", "qwen", "", 20, 5);
        assertEquals(Fingerprints.ingestFp(config, points), Fingerprints.ingestFp(config, points));
        assertEquals(Fingerprints.answerFp(config), Fingerprints.answerFp(config));
    }

    @Test
    void ingestFpSensitiveToModelBatchAndSource() throws Exception {
        List<EvalPoint> lme = List.of(new EvalPoint("p1", "L1", "lme-abc", 10,
                "t", "tr", List.of(), List.of(), null, null, null, null));
        List<EvalPoint> live = List.of(new EvalPoint("p2", "L1", "session-x", 10,
                "t", "tr", List.of(), List.of(), null, null, null, null));
        EvalConfig base = config("src/test/resources/lme/tiny.json", "qwen", "", 20, 5);

        assertNotEquals(Fingerprints.ingestFp(base, lme),
                Fingerprints.ingestFp(config("src/test/resources/lme/tiny.json", "glm", "", 20, 5), lme),
                "смена экстрактора = новый эксперимент");
        assertNotEquals(Fingerprints.ingestFp(base, lme),
                Fingerprints.ingestFp(config("src/test/resources/lme/tiny.json", "qwen", "", 50, 5), lme),
                "смена батчинга = новый эксперимент");
        assertNotEquals(Fingerprints.ingestFp(base, lme), Fingerprints.ingestFp(base, live),
                "LME-источник ≠ live-дескриптор");
        assertThrows(IllegalStateException.class,
                () -> Fingerprints.ingestFp(config("no/such/file.json", "qwen", "", 20, 5), lme),
                "битая ссылка на LME-файл — громкий отказ");
    }

    @Test
    void answerFpSensitiveToModelAndWindow() {
        EvalConfig base = config("", "qwen", "", 20, 5);
        assertNotEquals(Fingerprints.answerFp(base),
                Fingerprints.answerFp(config("", "qwen", "glm-answer", 20, 5)),
                "смена отвечающей модели — новые ответы в том же эксперименте");
        assertNotEquals(Fingerprints.answerFp(base),
                Fingerprints.answerFp(config("", "qwen", "", 20, 10)),
                "смена окна N — новые ответы");
        // fallback llm.answer.model → llm.model: одинаковый fp
        assertEquals(Fingerprints.answerFp(config("", "qwen", "", 20, 5)),
                Fingerprints.answerFp(config("", "other", "qwen", 20, 5)));
    }

    @Test
    void hexSha256Shape() {
        String fp = Fingerprints.answerFp(config("", "qwen", "", 20, 5));
        assertTrue(fp.matches("[0-9a-f]{64}"), "sha-256 hex");
    }
}
