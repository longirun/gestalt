package ru.longirun.gestalt.spike.dedup;

import ru.longirun.gestalt.spike.extract.ExtractedFact;
import ru.longirun.gestalt.spike.store.FactRepository;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/**
 * Двухшаговый дедуп (Р23): exact → near.
 * Подтверждение = reinforcement_count++, не INSERT.
 * Конфликт object у того же якоря — журнал (supersession вне среза).
 */
public final class DedupPipeline {

    public record DedupStats(int total, int inserted, int reinforced, int conflicts) {
    }

    private final FactRepository repository;
    private final NearDupMatcher nearDup;

    public DedupPipeline(FactRepository repository, NearDupMatcher nearDup) {
        this.repository = repository;
        this.nearDup = nearDup;
    }

    public DedupStats process(String ownerId, String sessionId, String projectId, Iterable<ExtractedFact> facts) throws SQLException {
        int total = 0;
        int inserted = 0;
        int reinforced = 0;
        int conflicts = 0;

        for (ExtractedFact fact : facts) {
            total++;
            if (fact.isState()) {
                Optional<FactRepository.StoredFact> candidate = repository.findExactCandidate(
                        ownerId,
                        fact.scope(),
                        projectId,
                        fact.subject(),
                        fact.predicate(),
                        fact.conditions());

                if (candidate.isPresent()) {
                    FactRepository.StoredFact existing = candidate.get();
                    String oldVal = ExactMatcher.normalize(existing.objectValue());
                    String newVal = ExactMatcher.normalize(fact.object());
                    if (Objects.equals(oldVal, newVal)) {
                        repository.incrementReinforcement(existing.id(), fact.evidenceMessageIds());
                        reinforced++;
                    } else {
                        System.out.printf("[DEDUP CONFLICT] Anchor (%s, %s): '%s' -> '%s'%n",
                                fact.subject(), fact.predicate(), existing.objectValue(), fact.object());
                        repository.insert(ownerId, sessionId, projectId, fact);
                        conflicts++;
                        inserted++;
                    }
                } else {
                    repository.insert(ownerId, sessionId, projectId, fact);
                    inserted++;
                }
            } else {
                // NARRATIVE и EVENT не дедуплицируются: каждый факт уникален
                repository.insert(ownerId, sessionId, projectId, fact);
                inserted++;
            }
        }

        return new DedupStats(total, inserted, reinforced, conflicts);
    }
}
