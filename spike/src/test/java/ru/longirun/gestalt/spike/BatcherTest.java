package ru.longirun.gestalt.spike;

import org.junit.jupiter.api.Test;
import ru.longirun.gestalt.spike.ingest.Batcher;
import ru.longirun.gestalt.spike.ingest.RawMessage;

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
                .batch(IntStream.rangeClosed(1, 5).mapToObj(i -> msg(i, 60)).toList());
        assertEquals(3, batches.size());
        assertTrue(batches.getFirst().size() == 2 && batches.get(1).size() == 2 && batches.getLast().size() == 1);
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
}
