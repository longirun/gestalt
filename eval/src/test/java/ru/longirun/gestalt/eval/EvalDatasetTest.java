package ru.longirun.gestalt.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Загрузка точек датасета из JSONL (ADR 28 §3.2). */
class EvalDatasetTest {

    @TempDir
    Path tempDir;

    private Path dataset(String... lines) throws IOException {
        Path file = tempDir.resolve("points.jsonl");
        Files.writeString(file, String.join("\n", lines) + "\n");
        return file;
    }

    @Test
    void loadsAllFieldsOfL1PointInFileOrder() throws IOException {
        Path file = dataset(
                "{\"id\":\"L1-000\",\"level\":\"L1\",\"sourceSession\":\"example-session\",\"sourceMessageId\":42,"
                        + "\"trigger\":\"продолжай\",\"truth\":\"переключился на ветку main\","
                        + "\"must\":[\"main\",\"rebase\"],\"mustNot\":[\"master\"],\"coverage\":\"факт о ветке в W\"}",
                "{\"id\":\"L1-001\",\"level\":\"L1\",\"sourceSession\":\"example-session\",\"sourceMessageId\":100,"
                        + "\"trigger\":\"что дальше\",\"truth\":\"отправил PR\",\"must\":[\"PR\"],\"mustNot\":[],"
                        + "\"coverage\":\"факт о процессе\"}");
        List<EvalPoint> points = EvalDataset.load(file);

        assertEquals(2, points.size());
        assertEquals("L1-000", points.get(0).id());
        assertEquals("L1-001", points.get(1).id());

        EvalPoint first = points.getFirst();
        assertEquals("L1", first.level());
        assertEquals("example-session", first.sourceSession());
        assertEquals(42L, first.sourceMessageId());
        assertEquals("продолжай", first.trigger());
        assertEquals("переключился на ветку main", first.truth());
        assertEquals(List.of("main", "rebase"), first.must());
        assertEquals(List.of("master"), first.mustNot());
        assertEquals("факт о ветке в W", first.coverage());
    }

    @Test
    void loadsCPointWithNullCoverageAndEmptyLists() throws IOException {
        Path file = dataset(
                "{\"id\":\"C-001\",\"level\":\"C\",\"sourceSession\":\"example-session\",\"sourceMessageId\":50,"
                        + "\"trigger\":\"нейтральный вопрос\",\"truth\":\"обычный ответ без памяти\","
                        + "\"must\":[],\"mustNot\":[]}");
        List<EvalPoint> points = EvalDataset.load(file);

        assertEquals(1, points.size());
        EvalPoint point = points.getFirst();
        assertEquals("C", point.level());
        assertEquals(50L, point.sourceMessageId());
        assertTrue(point.must().isEmpty());
        assertTrue(point.mustNot().isEmpty());
        assertNull(point.coverage());
    }

    @Test
    void skipsBlankLines() throws IOException {
        Path file = dataset(
                "",
                "{\"id\":\"L1-000\",\"level\":\"L1\",\"sourceSession\":\"s\",\"sourceMessageId\":1,"
                        + "\"trigger\":\"t\",\"truth\":\"r\",\"must\":[],\"mustNot\":[],\"coverage\":\"c\"}",
                "   ",
                "{\"id\":\"L1-001\",\"level\":\"L1\",\"sourceSession\":\"s\",\"sourceMessageId\":2,"
                        + "\"trigger\":\"t\",\"truth\":\"r\",\"must\":[],\"mustNot\":[],\"coverage\":\"c\"}",
                "");
        List<EvalPoint> points = EvalDataset.load(file);

        assertEquals(2, points.size());
        assertEquals(List.of("L1-000", "L1-001"), points.stream().map(EvalPoint::id).toList());
    }

    @Test
    void duplicateIdThrowsIllegalState() throws IOException {
        Path file = dataset(
                "{\"id\":\"L1-000\",\"level\":\"L1\",\"sourceSession\":\"s\",\"sourceMessageId\":1,"
                        + "\"trigger\":\"t\",\"truth\":\"r\",\"must\":[],\"mustNot\":[],\"coverage\":\"c\"}",
                "{\"id\":\"L1-000\",\"level\":\"L1\",\"sourceSession\":\"s\",\"sourceMessageId\":2,"
                        + "\"trigger\":\"t\",\"truth\":\"r\",\"must\":[],\"mustNot\":[],\"coverage\":\"c\"}");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> EvalDataset.load(file));
        assertTrue(e.getMessage().contains("L1-000"), "сообщение должно называть дубликат: " + e.getMessage());
    }

    @Test
    void missingFileThrowsIllegalState() {
        Path missing = tempDir.resolve("no-such-dataset.jsonl");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> EvalDataset.load(missing));
        assertTrue(e.getMessage().contains("dataset file not found"), e.getMessage());
    }
}
