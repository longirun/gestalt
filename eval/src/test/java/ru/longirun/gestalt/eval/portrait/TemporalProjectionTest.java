package ru.longirun.gestalt.eval.portrait;

import org.junit.jupiter.api.Test;
import ru.longirun.gestalt.eval.store.FactRepository.StoredFact;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemporalProjectionTest {

    private StoredFact fact(List<Long> evidence, int reinforcementCount) {
        return new StoredFact(UUID.randomUUID(), "user:u", "session:s", "project:p", "WORLD",
                "USER", "fact", "subj", "pred", "obj", "statement text", Map.of("k", "v"),
                reinforcementCount, 1.0, OffsetDateTime.parse("2026-09-18T22:51:00Z"), evidence);
    }

    @Test
    void includesOnlyFactsBornBeforeBound() {
        StoredFact bornEarly = fact(List.of(10L), 1);
        StoredFact bornLate = fact(List.of(150L), 1);
        StoredFact emptyEvidence = fact(List.of(), 1);

        List<StoredFact> projected = TemporalProjection.atBound(
                List.of(bornEarly, bornLate, emptyEvidence), 100);

        assertEquals(1, projected.size());
        assertEquals(bornEarly.id(), projected.getFirst().id());
    }

    @Test
    void reinforcementIsPrefixCountCappedByCurrent() {
        StoredFact prefixThreeRcTwo = fact(List.of(10L, 20L, 30L), 2);
        StoredFact prefixThreeRcOne = fact(List.of(10L, 20L, 30L), 1);

        List<StoredFact> projected = TemporalProjection.atBound(
                List.of(prefixThreeRcTwo, prefixThreeRcOne), 100);

        assertEquals(2, projected.get(0).reinforcementCount());
        assertEquals(1, projected.get(1).reinforcementCount());
    }

    @Test
    void spanBoundFactCountsOnlyEarlyEvidence() {
        StoredFact spanBound = fact(List.of(10L, 200L), 1);

        List<StoredFact> projected = TemporalProjection.atBound(List.of(spanBound), 100);

        assertEquals(1, projected.size());
        assertEquals(1, projected.getFirst().reinforcementCount());
        assertEquals(List.of(10L), projected.getFirst().evidenceMessageIds());
    }

    @Test
    void preservesFactContentAndMeta() {
        StoredFact source = fact(List.of(10L, 200L), 1);

        List<StoredFact> projected = TemporalProjection.atBound(List.of(source), 100);

        StoredFact p = projected.getFirst();
        assertEquals(source.statement(), p.statement());
        assertEquals(source.subjectNorm(), p.subjectNorm());
        assertEquals(source.createdAt(), p.createdAt());
        assertEquals(source.conditions(), p.conditions());
        assertTrue(p.evidenceMessageIds().stream().allMatch(id -> id <= 100));
    }
}
