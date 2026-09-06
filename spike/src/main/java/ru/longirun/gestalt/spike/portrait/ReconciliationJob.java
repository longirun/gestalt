package ru.longirun.gestalt.spike.portrait;

/**
 * Джоба пересборки слепка (Р19, Р10): cutoff-инвалидация
 * (max(system_time входов) > cutoff), lease+backoff, poison-изоляция.
 * TODO(Р34): цикл джобы, stale-предикат, синхронный fallback.
 */
public final class ReconciliationJob {

    public void runOnce() {
        throw new UnsupportedOperationException("TODO(Р19)");
    }
}
