package ru.longirun.gestalt.eval.ingest;

import java.util.ArrayList;
import java.util.List;

/**
 * Pass-Once батчер (Р8): один хронологический проход, флеш по границе
 * (15-20 реплик / 1500-2000 токенов — калибровка драфта 23 §5).
 * Батч закрывается ДО добавления переполняющей реплики (pre-check);
 * реплика-монстр (сама больше лимита) уходит отдельным батчом.
 */
public final class Batcher {

    private final int maxMessages;
    private final int maxTokens;

    public Batcher(int maxMessages, int maxTokens) {
        if (maxMessages <= 0 || maxTokens <= 0) {
            throw new IllegalArgumentException("batch limits must be positive");
        }
        this.maxMessages = maxMessages;
        this.maxTokens = maxTokens;
    }

    public List<List<RawMessage>> batch(List<RawMessage> messages) {
        List<List<RawMessage>> batches = new ArrayList<>();
        List<RawMessage> current = new ArrayList<>();
        int tokens = 0;
        for (RawMessage message : messages) {
            // pre-check: закрываем батч ДО добавления переполняющей реплики —
            // пост-чек позволял одиночному отчёту в 5k+ токенов раздуть промпт до 7k+
            if (!current.isEmpty() && tokens + message.tokenCount() > maxTokens) {
                batches.add(List.copyOf(current));
                current = new ArrayList<>();
                tokens = 0;
            }
            current.add(message);
            tokens += message.tokenCount();
            if (current.size() >= maxMessages || tokens >= maxTokens) {
                batches.add(List.copyOf(current));
                current = new ArrayList<>();
                tokens = 0;
            }
        }
        if (!current.isEmpty()) {
            batches.add(List.copyOf(current));
        }
        return batches;
    }
}
