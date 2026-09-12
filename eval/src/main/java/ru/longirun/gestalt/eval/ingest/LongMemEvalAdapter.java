package ru.longirun.gestalt.eval.ingest;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * LongMemEval как источник лога (positive control каркаса, план 31; задел Р37 — внешние
 * бенчмарки на том же каркасе). Каждый вопрос — изолированный микромир сессий: sourceSession
 * точки имеет вид {@code lme-<question_id>} и же служит портрет-проектом (слепки разных
 * вопросов не смешиваются). Реплика-вопрос добавляется в конец лога последней (peer user):
 * слепок M-exclusive строится до неё — вопрос не инжестится, инвариант replay сохраняется.
 * Сессии упорядочиваются по haystack_dates (стабильно), вопрос — после всех сессий.
 */
public final class LongMemEvalAdapter {

    public static final String SESSION_PREFIX = "lme-";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Формат дат LongMemEval: "2023/05/20 (Sat) 02:21". */
    private static final DateTimeFormatter LME_DATE =
            DateTimeFormatter.ofPattern("yyyy/M/d '('EEE')' HH:mm", Locale.ENGLISH);

    /** Одна запись LongMemEval: вопрос + его сессии (raw, без упорядочивания). */
    public record LmeRecord(
            String questionId,
            String questionType,
            String question,
            String answer,
            String questionDate,
            List<String> haystackDates,
            List<List<Dialog>> haystackSessions) {
    }

    public record Dialog(String role, String content) {
    }

    private final Path file;

    public LongMemEvalAdapter(Path file) {
        this.file = file;
    }

    /** Streaming-проход по всем записям файла (файл может быть сотни MB — не держим его в памяти). */
    public void forEachRecord(Consumer<LmeRecord> consumer) throws IOException {
        try (JsonParser parser = new JsonFactory().createParser(file.toFile())) {
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw new IOException("LongMemEval file must be a JSON array: " + file);
            }
            while (parser.nextToken() == JsonToken.START_OBJECT) {
                JsonNode node = MAPPER.readTree(parser);
                List<List<Dialog>> sessions = new ArrayList<>();
                for (JsonNode session : node.path("haystack_sessions")) {
                    List<Dialog> dialogs = new ArrayList<>();
                    for (JsonNode dialog : session) {
                        dialogs.add(new Dialog(dialog.path("role").asText(), dialog.path("content").asText()));
                    }
                    sessions.add(dialogs);
                }
                List<String> dates = new ArrayList<>();
                for (JsonNode date : node.path("haystack_dates")) {
                    dates.add(date.asText());
                }
                consumer.accept(new LmeRecord(
                        node.path("question_id").asText(),
                        node.path("question_type").asText(),
                        node.path("question").asText(),
                        node.path("answer").asText(),
                        node.path("question_date").asText(),
                        dates,
                        sessions));
            }
        }
    }

    /** Лог вопроса (сессии по датам + реплика-вопрос последней); кидает исключение, если qid нет в файле. */
    public List<RawMessage> messages(String questionId) throws IOException {
        AtomicReference<List<RawMessage>> found = new AtomicReference<>();
        forEachRecord(record -> {
            if (record.questionId().equals(questionId)) {
                found.set(buildLog(record));
            }
        });
        List<RawMessage> log = found.get();
        if (log == null) {
            throw new IllegalStateException("question_id '%s' not found in %s".formatted(questionId, file));
        }
        return log;
    }

    /** Общий порядок реплик для конвертера и replay: идентичная нумерация id обязательна. */
    public static List<RawMessage> buildLog(LmeRecord record) {
        List<RawMessage> log = new ArrayList<>();
        Integer[] order = new Integer[record.haystackSessions().size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        // стабильная сортировка индексов сессий по дате: порядок файла внутри равных дат сохраняется
        Arrays.sort(order, Comparator.comparing(index -> parseDate(record.haystackDates().get(index)), Comparator.nullsLast(Comparator.naturalOrder())));
        long id = 0;
        for (int index : order) {
            OffsetDateTime at = parseDate(record.haystackDates().get(index));
            for (Dialog dialog : record.haystackSessions().get(index)) {
                log.add(new RawMessage(++id, SESSION_PREFIX + record.questionId(), dialog.role(),
                        dialog.content(), Math.max(1, dialog.content().length() / 4), at));
            }
        }
        log.add(new RawMessage(++id, SESSION_PREFIX + record.questionId(), "user", record.question(),
                Math.max(1, record.question().length() / 4), parseDate(record.questionDate())));
        return log;
    }

    private static OffsetDateTime parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            LocalDateTime local = LocalDateTime.parse(raw, LME_DATE);
            return OffsetDateTime.of(local, ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
