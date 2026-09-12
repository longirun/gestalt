package ru.longirun.gestalt.eval;

import org.junit.jupiter.api.Test;
import ru.longirun.gestalt.eval.ingest.LongMemEvalAdapter;
import ru.longirun.gestalt.eval.ingest.RawMessage;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LongMemEvalAdapterTest {

    private final LongMemEvalAdapter adapter = new LongMemEvalAdapter(
            Path.of("src/test/resources/lme/minimal.json"));

    @Test
    void sessionsOrderedByDateQuestionLast() throws Exception {
        List<RawMessage> log = adapter.messages("q_alpha");

        assertEquals(7, log.size());
        for (int i = 0; i < log.size(); i++) {
            assertEquals(i + 1, log.get(i).id(), "ids must be sequential from 1");
            assertEquals("lme-q_alpha", log.get(i).sessionName());
            assertNotNull(log.get(i).createdAt(), "LME dates must parse");
        }
        // сессии отсортированы по датам: 05-20 (s2) → 05-21 07:00 (s3) → 05-21 09:00 (s1), вопрос последним
        assertEquals("saturday msg", log.get(0).content());
        assertEquals("I like blue color", log.get(2).content());
        assertEquals("sunday evening msg", log.get(3).content());
        assertEquals("hello from monday morning", log.get(4).content());

        RawMessage question = log.get(6);
        assertEquals("user", question.peerName());
        assertEquals("What is my favorite color?", question.content());
    }

    @Test
    void unknownQuestionIdThrows() {
        assertThrows(IllegalStateException.class, () -> adapter.messages("q_missing"));
    }

    @Test
    void forEachRecordSeesAllRecords() throws Exception {
        List<String> ids = new java.util.ArrayList<>();
        adapter.forEachRecord(record -> ids.add(record.questionId()));
        assertEquals(List.of("q_alpha", "q_beta", "q_gamma"), ids);
        assertEquals(3, adapter.messages("q_beta").size(), "beta: 2 dialogs + question");
    }
}
