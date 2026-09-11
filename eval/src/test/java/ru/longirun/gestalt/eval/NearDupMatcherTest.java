package ru.longirun.gestalt.eval;

import org.junit.jupiter.api.Test;
import ru.longirun.gestalt.eval.dedup.NearDupMatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NearDupMatcherTest {

    @Test
    void identicalStringsHaveSimilarityOne() {
        assertEquals(1.0, NearDupMatcher.trigramSimilarity("project:jrestly", "project:jrestly"), 0.001);
        assertEquals(1.0, NearDupMatcher.trigramSimilarity("project:jRestly", "  project:jrestly  "), 0.001);
    }

    @Test
    void similarAliasesHaveHighSimilarity() {
        double sim = NearDupMatcher.trigramSimilarity("project:jrestly", "project:jRestly");
        assertEquals(1.0, sim, 0.001);

        double simUser = NearDupMatcher.trigramSimilarity("user:sinyagovsky", "user:sinyagovskiy");
        assertTrue(simUser > 0.7, "Expected >0.7 similarity, got " + simUser);
    }

    @Test
    void completelyDifferentStringsHaveLowSimilarity() {
        double sim = NearDupMatcher.trigramSimilarity("project:jrestly", "user:anton");
        assertTrue(sim < 0.2, "Expected low similarity, got " + sim);
    }

    @Test
    void emptyStringsHandleSafely() {
        assertEquals(1.0, NearDupMatcher.trigramSimilarity("", ""), 0.001);
        assertEquals(0.0, NearDupMatcher.trigramSimilarity("abc", ""), 0.001);
    }
}
