package ru.longirun.gestalt.eval;

import org.junit.jupiter.api.Test;
import ru.longirun.gestalt.eval.ingest.RawMessage;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Плечи E3: дайджест слепка, системный промпт (A с памятью / B без), стенограмма окна.
 */
class ArmsTest {

    @Test
    void digestPrefersStatementAndSkipsEmptySections() {
        String snapshot = """
                {"critical":[{"subject":"user","predicate":"","object":"","statement":"Never mention X"}],
                 "constructs":[{"subject":"user","predicate":"education_degree","object":"Business Administration"}],
                 "preferences":[]}
                """;

        String digest = Arms.memoryDigest(snapshot);

        assertTrue(digest.contains("[critical]"));
        assertTrue(digest.contains("- Never mention X"));
        assertTrue(digest.contains("user · education_degree · Business Administration"));
        assertFalse(digest.contains("[preferences]"), "пустая секция не попадает в дайджест");
    }

    @Test
    void emptySnapshotYieldsEmptyDigest() {
        assertEquals("", Arms.memoryDigest(null));
        assertEquals("", Arms.memoryDigest("{}"));
        assertEquals("", Arms.memoryDigest("{\"critical\":[]}"));
    }

    @Test
    void systemPromptsWithMemoryDifferOnlyByMemoryBlock() {
        String with = Arms.systemPrompt("some memory");
        String without = Arms.systemPrompt(null);

        assertTrue(with.startsWith(without), "каркас промпта одинаков, память — суффикс");
        assertTrue(with.contains("Long-term memory"));
        assertFalse(without.contains("Long-term memory"));
    }

    @Test
    void transcriptMapsRolesAndEndsWithQuestion() {
        List<RawMessage> window = List.of(
                new RawMessage(8, "s", "user", "hello there", 2, OffsetDateTime.now()),
                new RawMessage(9, "s", "assistant", "hi!", 1, OffsetDateTime.now()),
                new RawMessage(10, "s", "Alice", "peer-name survives", 2, OffsetDateTime.now()));

        String payload = Arms.userPayload(window, "What degree?");

        assertTrue(payload.contains("<User>: hello there"));
        assertTrue(payload.contains("<Assistant>: hi!"));
        assertTrue(payload.contains("<Alice>: peer-name survives"));
        assertTrue(payload.trim().endsWith("Question: What degree?\n\nAnswer:")
                || payload.contains("Question: What degree?"));
    }

    @Test
    void windowIsStrictlyBetweenW0AndM() {
        List<RawMessage> log = new java.util.ArrayList<>();
        for (long id = 1; id <= 13; id++) {
            log.add(new RawMessage(id, "s", "user", "m" + id, 1, OffsetDateTime.now()));
        }

        List<RawMessage> window = Arms.windowMessages(log, 7, 13);

        assertEquals(List.of(8L, 9L, 10L, 11L, 12L),
                window.stream().map(RawMessage::id).toList());
    }
}
