package ru.longirun.gestalt.eval;

import org.junit.jupiter.api.Test;

import ru.longirun.gestalt.eval.portrait.SnapshotDiff;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SnapshotDiffTest {

    @Test
    void newFactsAreThoseAppearedInAfterOnly() {
        String before = """
                {"critical":[{"id":"11111111-1111-1111-1111-111111111111","subject":"a"}],
                 "constructs":[{"id":"22222222-2222-2222-2222-222222222222","subject":"b"}]}""";
        String after = """
                {"critical":[{"id":"11111111-1111-1111-1111-111111111111","subject":"a"}],
                 "constructs":[{"id":"22222222-2222-2222-2222-222222222222","subject":"b"}],
                 "preferences":[{"id":"33333333-3333-3333-3333-333333333333","subject":"c","statement":"prefers X"}]}""";

        List<SnapshotDiff.FactRef> appeared = SnapshotDiff.newFacts(before, after);

        assertEquals(1, appeared.size());
        assertEquals("33333333-3333-3333-3333-333333333333", appeared.getFirst().id().toString());
        assertEquals("preferences", appeared.getFirst().section());
        assertEquals("prefers X", appeared.getFirst().statement());
    }

    @Test
    void emptyBeforeMeansEverythingIsNew() {
        String after = """
                {"critical":[{"id":"11111111-1111-1111-1111-111111111111","subject":"a"}]}""";

        assertEquals(1, SnapshotDiff.newFacts("{}", after).size());
    }
}
