package ru.longirun.gestalt.spike.extract;

import ru.longirun.gestalt.spike.ingest.RawMessage;
import ru.longirun.gestalt.spike.llm.LlmClient;

import java.util.List;

/**
 * Encoding, fast path (Р6/Р8): один LLM-вызов на батч.
 * TODO(Р34): сериализация батча в user-payload, разбор JSON-ответа,
 * валидация схемы факта, journal батча в out/ (стоимость токенов).
 */
public final class LlmBatchExtractor implements FactExtractor {

    private final LlmClient llm;

    public LlmBatchExtractor(LlmClient llm) {
        this.llm = llm;
    }

    @Override
    public List<ExtractedFact> extract(List<RawMessage> batch) {
        throw new UnsupportedOperationException("TODO(Р34): wire ExtractionPrompt + LlmClient");
    }
}
