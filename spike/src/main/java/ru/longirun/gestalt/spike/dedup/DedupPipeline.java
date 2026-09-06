package ru.longirun.gestalt.spike.dedup;

import ru.longirun.gestalt.spike.extract.ExtractedFact;
import ru.longirun.gestalt.spike.store.FactRepository;

/**
 * Двухшаговый дедуп (Р23): exact → near. Подтверждение = reinforcement_count++,
 * не INSERT. Конфликт object у того же якоря — журнал (supersession вне среза).
 * TODO(Р34): orchestration exact/near, write-path в FactRepository.
 */
public final class DedupPipeline {

    private final FactRepository repository;
    private final NearDupMatcher nearDup;

    public DedupPipeline(FactRepository repository, NearDupMatcher nearDup) {
        this.repository = repository;
        this.nearDup = nearDup;
    }

    public void process(Iterable<ExtractedFact> facts) {
        throw new UnsupportedOperationException("TODO(Р34): exact key -> INSERT | reinforcement | conflict-log");
    }
}
