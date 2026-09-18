package ru.longirun.gestalt.eval.dedup;

import ru.longirun.gestalt.eval.extract.ExtractedFact;
import ru.longirun.gestalt.eval.store.FactRepository;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Двухшаговый дедуп (Р23): exact → near.
 * Подтверждение = reinforcement_count++, не INSERT.
 * Конфликт object у того же якоря — журнал (supersession вне среза).
 */
public final class DedupPipeline {

    private final FactRepository repository;
    private final NearDupMatcher nearDup;

    public DedupPipeline(FactRepository repository, NearDupMatcher nearDup) {
        this.repository = repository;
        this.nearDup = nearDup;
    }

    public void process(String ownerId, String sessionId, String projectId, Iterable<ExtractedFact> facts) throws SQLException {
        for (ExtractedFact fact : facts) {
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
                    } else {
                        System.out.printf("[DEDUP CONFLICT] Anchor (%s, %s): '%s' -> '%s'%n",
                                fact.subject(), fact.predicate(), existing.objectValue(), fact.object());
                        repository.insert(ownerId, sessionId, projectId, fact);
                    }
                } else {
                    List<NearDupMatcher.NearCandidate> nearMatches = nearDup.findCandidates(repository.connection(), fact.subject());
                    if (!nearMatches.isEmpty()) {
                        NearDupMatcher.NearCandidate top = nearMatches.getFirst();
                        if (!ExactMatcher.normalize(top.subjectNorm()).equals(ExactMatcher.normalize(fact.subject()))) {
                            System.out.printf("[NEAR DUP CANDIDATE] Subject '%s' ~ '%s' (sim: %.2f)%n",
                                    fact.subject(), top.subjectNorm(), top.similarity());
                        }
                    }
                    repository.insert(ownerId, sessionId, projectId, fact);
                }
            } else {
                // NARRATIVE и EVENT не дедуплицируются: каждый факт уникален
                repository.insert(ownerId, sessionId, projectId, fact);
            }
        }
    }
}
