package ru.longirun.gestalt.eval;

import org.junit.jupiter.api.Test;
import ru.longirun.gestalt.eval.ingest.Batcher;
import ru.longirun.gestalt.eval.ingest.RawMessage;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatcherTest {

    private RawMessage msg(long id, int tokens) {
        return new RawMessage(id, "s", "user", "content", tokens, OffsetDateTime.now());
    }

    @Test
    void flushesByTokenBudget() {
        List<List<RawMessage>> batches = new Batcher(20, 100)
                .batch(IntStream.rangeClosed(1, 5).mapToObj(i -> msg(i, 30)).toList());
        assertEquals(2, batches.size());
        assertTrue(batches.getFirst().size() == 3 && batches.getLast().size() == 2);
    }

    @Test
    void oversizedMessageDoesNotInflateFilledBatch() {
        RawMessage n1 = msg(1, 500);
        RawMessage n2 = msg(2, 400);
        RawMessage huge = msg(3, 5000);
        RawMessage n3 = msg(4, 100);
        List<List<RawMessage>> batches = new Batcher(20, 1000).batch(List.of(n1, n2, huge, n3));
        assertEquals(3, batches.size());
        assertTrue(batches.get(0).size() == 2 && batches.get(1).size() == 1 && batches.get(2).size() == 1);
        assertEquals(huge, batches.get(1).getFirst());
    }

    @Test
    void flushesByMessageCount() {
        List<List<RawMessage>> batches = new Batcher(2, 10_000)
                .batch(IntStream.rangeClosed(1, 5).mapToObj(i -> msg(i, 1)).toList());
        assertEquals(3, batches.size());
        assertTrue(batches.stream().allMatch(b -> b.size() <= 2));
    }

    @Test
    void passOnceKeepsChronologyAndCompleteness() {
        List<RawMessage> messages = IntStream.rangeClosed(1, 7).mapToObj(i -> msg(i, 3)).toList();
        List<RawMessage> flat = new Batcher(3, 10_000).batch(messages).stream()
                .flatMap(List::stream).toList();
        assertEquals(messages, flat);
    }

    @Test
    void handlesEmptyMessagesList() {
        List<List<RawMessage>> batches = new Batcher(10, 1000).batch(List.of());
        assertTrue(batches.isEmpty());
    }

    @Test
    void singleOversizedMessageFormsOwnBatch() {
        RawMessage huge = msg(1, 5000);
        RawMessage normal = msg(2, 50);
        List<List<RawMessage>> batches = new Batcher(10, 1000).batch(List.of(huge, normal));
        assertEquals(2, batches.size());
        assertEquals(1, batches.getFirst().size());
        assertEquals(1, batches.getLast().size());
    }
}
