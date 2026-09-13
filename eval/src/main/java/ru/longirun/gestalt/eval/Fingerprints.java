package ru.longirun.gestalt.eval;

import ru.longirun.gestalt.eval.extract.ExtractionPrompt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * Флаги совместимости прибора (план 31 §7.2) — формализация «продолжать или с нуля»:
 * ingest_fp охраняет факты/слепки/чекпоинты (смена = новый эксперимент, старый archived),
 * answer_fp охраняет answers/verdicts (смена = перезапись ответов новым run того же
 * эксперимента, слепки переиспользуются). Состав — по §7.2; датасет для LME — контент
 * LongMemEval-файла (source инжеста), для live — дескриптор среза Honcho.
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /** hash(датасет-источник + экстрактор-модель + extraction-промпт + батчинг). */
    public static String ingestFp(EvalConfig config, List<EvalPoint> points) throws Exception {
        StringBuilder sb = new StringBuilder("ingest-v1\n");
        sb.append("source: ").append(sourceIdentity(config, points)).append('\n');
        sb.append("model: ").append(config.llmModel()).append('\n');
        sb.append("prompt: ").append(sha256(ExtractionPrompt.SYSTEM)).append('\n');
        sb.append("batch: ").append(config.batchMaxMessages()).append('/').append(config.batchMaxTokens());
        return sha256(sb.toString());
    }

    /** hash(отвечающая модель + arms-промпт + окно N); шаблон — systemPrompt с sentinel-дайджестом. */
    public static String answerFp(EvalConfig config) {
        StringBuilder sb = new StringBuilder("answer-v1\n");
        sb.append("model: ").append(answerModel(config)).append('\n');
        sb.append("prompt: ").append(sha256(Arms.systemPrompt("<fingerprint-sentinel>"))).append('\n');
        sb.append("window: ").append(config.windowSize());
        return sha256(sb.toString());
    }

    /**
     * Идентичность инжест-источника по фактическому составу датасета: LME-точки — контент
     * LongMemEval-файла (он и есть source инжеста), live-точки — дескриптор среза Honcho
     * (контент среза живёт в БД-источнике). Смешанный датасет покрывает оба.
     */
    static String sourceIdentity(EvalConfig config, List<EvalPoint> points) throws Exception {
        boolean hasLme = points.stream().anyMatch(p -> EvalRunner.isLme(config, p.sourceSession()));
        boolean hasLive = points.stream().anyMatch(p -> !EvalRunner.isLme(config, p.sourceSession()));
        StringBuilder sb = new StringBuilder();
        if (hasLme) {
            Path file = EvalPaths.resolve(config.sourceLmeFile());
            if (!Files.exists(file)) {
                throw new IllegalStateException("source.lme.file not found: " + file);
            }
            sb.append("lme:").append(sha256(Files.readString(file)));
        }
        if (hasLive) {
            if (hasLme) {
                sb.append('+');
            }
            sb.append("honcho:").append(config.sourceDbUrl())
                    .append('|').append(config.sourceDayFrom()).append("..").append(config.sourceDayTo())
                    .append(config.sourceFixture().isBlank() ? "" : "|fixture:" + config.sourceFixture());
        }
        if (sb.isEmpty()) {
            throw new IllegalStateException("empty dataset: no source identity for fingerprint");
        }
        return sb.toString();
    }

    static String answerModel(EvalConfig config) {
        return config.llmAnswerModel().isBlank() ? config.llmModel() : config.llmAnswerModel();
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
