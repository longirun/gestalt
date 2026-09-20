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
                "", "", "",
                batchMessages, 2000, window, 50, "user:t", "project:t", "dataset/points.jsonl");
    }

    /** Копия конфига с включённой селекцией (группа llm.embedding.* целиком). */
    private static EvalConfig withSelection(EvalConfig c, String embeddingModel, int topK) {
        return new EvalConfig(
                c.sourceDbUrl(), c.sourceDbUser(), c.sourceDbPassword(),
                c.sourceDayFrom(), c.sourceDayTo(), c.sourceFixture(), c.sourceLmeFile(),
                c.targetDbUrl(), c.targetDbUser(), c.targetDbPassword(),
                c.llmBaseUrl(), c.llmApiKey(), c.llmModel(), c.llmReasoningEffort(),
                c.llmAnswerBaseUrl(), c.llmAnswerApiKey(), c.llmAnswerModel(), c.llmAnswerReasoningEffort(),
                "https://embed", "embed-key", embeddingModel,
                c.batchMaxMessages(), c.batchMaxTokens(), c.windowSize(), topK,
                c.portraitOwner(), c.portraitProject(), c.datasetFile());
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
    }

    @Test
    void answerFpSensitiveToSelectionParams() {
        EvalConfig off = config("", "qwen", "", 20, 5);
        EvalConfig on = withSelection(off, "bge-m3", 50);

        assertNotEquals(Fingerprints.answerFp(off), Fingerprints.answerFp(on),
                "включение селекции = другой дайджест плечу A — перезапись ответов");
        assertNotEquals(Fingerprints.answerFp(on),
                Fingerprints.answerFp(withSelection(off, "bge-m3", 30)),
                "смена top-K — другой состав дайджеста, новые ответы");
        assertNotEquals(Fingerprints.answerFp(on),
                Fingerprints.answerFp(withSelection(off, "e5-mistral", 50)),
                "смена эмбеддинг-модели — другое пространство близости, новые ответы");
    }

    @Test
    void selectionOffKeepsPreSelectionFpAndIgnoresTopK() {
        EvalConfig off = config("", "qwen", "", 20, 5);
        EvalConfig otherTopK = new EvalConfig(
                off.sourceDbUrl(), off.sourceDbUser(), off.sourceDbPassword(),
                off.sourceDayFrom(), off.sourceDayTo(), off.sourceFixture(), off.sourceLmeFile(),
                off.targetDbUrl(), off.targetDbUser(), off.targetDbPassword(),
                off.llmBaseUrl(), off.llmApiKey(), off.llmModel(), off.llmReasoningEffort(),
                off.llmAnswerBaseUrl(), off.llmAnswerApiKey(), off.llmAnswerModel(), off.llmAnswerReasoningEffort(),
                off.llmEmbeddingBaseUrl(), off.llmEmbeddingApiKey(), off.llmEmbeddingModel(),
                off.batchMaxMessages(), off.batchMaxTokens(), off.windowSize(), 10,
                off.portraitOwner(), off.portraitProject(), off.datasetFile());
        assertEquals(Fingerprints.answerFp(off), Fingerprints.answerFp(otherTopK),
                "без селекции digest.top-k не входит в fp: кэш E6 не инвалидируется");
    }

    @Test
    void fpSensitiveToReasoningEffort() throws Exception {
        List<EvalPoint> lme = List.of(new EvalPoint("p1", "L1", "lme-abc", 10,
                "t", "tr", List.of(), List.of(), null, null, null, null));
        EvalConfig base = config("src/test/resources/lme/tiny.json", "qwen", "", 20, 5);

        assertNotEquals(Fingerprints.ingestFp(base, lme),
                Fingerprints.ingestFp(withEfforts(base, "high", "low"), lme),
                "смена reasoning-effort экстрактора = новый эксперимент");
        assertNotEquals(Fingerprints.answerFp(base),
                Fingerprints.answerFp(withEfforts(base, "low", "high")),
                "смена reasoning-effort отвечающей модели — новые ответы");
    }

    @Test
    void pointFpGuardsTriggerAndPosition() {
        EvalPoint base = new EvalPoint("p1", "L1", "session-x", 42,
                "какой сейчас план?", "tr", List.of(), List.of(), null, null, null, null);
        assertEquals(Fingerprints.pointFp("afp", base), Fingerprints.pointFp("afp", base),
                "стабильность на одном входе");
        assertNotEquals(Fingerprints.pointFp("afp", base),
                Fingerprints.pointFp("afp", new EvalPoint("p1", "L1", "session-x", 42,
                        "другой вопрос", "tr", List.of(), List.of(), null, null, null, null)),
                "правка trigger в разметке — перегенерация точки");
        assertNotEquals(Fingerprints.pointFp("afp", base),
                Fingerprints.pointFp("afp", new EvalPoint("p1", "L1", "session-y", 43,
                        "какой сейчас план?", "tr", List.of(), List.of(), null, null, null, null)),
                "сдвиг позиции (сессия/сообщение) — новое окно, перегенерация");
        assertNotEquals(Fingerprints.pointFp("afp", base), Fingerprints.pointFp("afp-2", base),
                "смена answer_fp инвалидирует строки точек");
        assertEquals(Fingerprints.pointFp("afp", base),
                Fingerprints.pointFp("afp", new EvalPoint("p1", "L1", "session-x", 42,
                        "какой сейчас план?", "другая истина", List.of("must"), List.of("must-not"),
                        "pattern", "coverage", "fork", 7L)),
                "вердикт-поля (must/truth/…) — входы оракулов, кэш ответов не трогают");
    }

    /** Копия конфига с подменой reasoning-effort'ов (у ответов fallback'а на llm.* нет). */
    private static EvalConfig withEfforts(EvalConfig c, String effort, String answerEffort) {
        return new EvalConfig(
                c.sourceDbUrl(), c.sourceDbUser(), c.sourceDbPassword(),
                c.sourceDayFrom(), c.sourceDayTo(), c.sourceFixture(), c.sourceLmeFile(),
                c.targetDbUrl(), c.targetDbUser(), c.targetDbPassword(),
                c.llmBaseUrl(), c.llmApiKey(), c.llmModel(), effort,
                c.llmAnswerBaseUrl(), c.llmAnswerApiKey(), c.llmAnswerModel(), answerEffort,
                c.llmEmbeddingBaseUrl(), c.llmEmbeddingApiKey(), c.llmEmbeddingModel(),
                c.batchMaxMessages(), c.batchMaxTokens(), c.windowSize(), c.digestTopK(),
                c.portraitOwner(), c.portraitProject(), c.datasetFile());
    }

    @Test
    void hexSha256Shape() {
        String fp = Fingerprints.answerFp(config("", "qwen", "", 20, 5));
        assertTrue(fp.matches("[0-9a-f]{64}"), "sha-256 hex");
    }
}
