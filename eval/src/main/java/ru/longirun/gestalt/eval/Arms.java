package ru.longirun.gestalt.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.longirun.gestalt.eval.ingest.RawMessage;
import ru.longirun.gestalt.eval.llm.LlmClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Стадия E3 — плечи A/B (ADR 28 §3.3): оба плеча отвечают на триггер M с идентичным
 * видимым контекстом — окном (W0, M) + вопросом; плечо A дополнительно получает дайджест
 * PortraitSnapshot в системном промпте, B — контроль без памяти. Одинаковая модель
 * (llm.answer.* с fallback на llm.*), temperature 0. Контракт вывода — viewer
 * (EvalViewerTest /api/answers): out/answers/&lt;pointId&gt;.{a,b}.json
 * {answer, model, tokens, latencyMs, cached, at, promptTokens, completionTokens};
 * существующие файлы не перегенерируются — кэш плеч замораживается (ADR 28).
 */
public final class Arms {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Arms() {
    }

    /** Дайджест слепка для инъекции плечу A: секции critical/constructs/preferences → строки-стейтменты. */
    static String memoryDigest(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try {
            JsonNode root = MAPPER.readTree(snapshotJson);
            for (String section : List.of("critical", "constructs", "preferences")) {
                JsonNode facts = root.path(section);
                if (!facts.isArray() || facts.isEmpty()) {
                    continue;
                }
                sb.append("[").append(section).append("]\n");
                for (JsonNode fact : facts) {
                    String statement = fact.path("statement").asText("");
                    String line = !statement.isBlank()
                            ? statement
                            : spo(fact.path("subject").asText(""),
                                  fact.path("predicate").asText(""),
                                  fact.path("object").asText(""));
                    if (!line.isBlank()) {
                        sb.append("- ").append(line).append('\n');
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("unreadable snapshot json: " + e.getMessage(), e);
        }
        return sb.toString().strip();
    }

    private static String spo(String subject, String predicate, String object) {
        StringBuilder sb = new StringBuilder();
        for (String part : List.of(subject, predicate, object)) {
            if (part != null && !part.isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append(" · ");
                }
                sb.append(part);
            }
        }
        return sb.toString();
    }

    /** Системный промпт: A — с блоком памяти, B — тот же каркас без него. */
    static String systemPrompt(String memoryDigest) {
        String base = "You are a helpful assistant. Answer the user's question briefly and factually, "
                + "based on the provided recent conversation.";
        if (memoryDigest == null || memoryDigest.isBlank()) {
            return base;
        }
        return base + "\n\n=== Long-term memory about the user (from earlier conversations) ===\n"
                + memoryDigest;
    }

    /** Пользовательская часть: стенограмма окна (W0, M) + вопрос M (оба плеча видят одинаково). */
    static String userPayload(List<RawMessage> window, String trigger) {
        StringBuilder sb = new StringBuilder("Recent conversation:\n");
        for (RawMessage message : window) {
            sb.append('<').append(role(message.peerName())).append(">: ")
              .append(message.content() == null ? "" : message.content().strip()).append('\n');
        }
        return sb.append("\nQuestion: ").append(trigger == null ? "" : trigger.strip())
                .append("\n\nAnswer:").toString();
    }

    private static String role(String peer) {
        if (peer == null) {
            return "User";
        }
        return switch (peer.toLowerCase()) {
            case "user", "human" -> "User";
            case "assistant", "ai" -> "Assistant";
            default -> peer;
        };
    }

    /** Реплики окна (W0, M): строго между границей W0 и триггером M (§2.9). */
    static List<RawMessage> windowMessages(List<RawMessage> log, long w0, long m) {
        List<RawMessage> window = new ArrayList<>();
        for (RawMessage message : log) {
            if (message.id() > w0 && message.id() < m) {
                window.add(message);
            }
        }
        return window;
    }

    /**
     * Прогон плеч по точкам датасета: `arms [limit]` — limit = сколько новых пар сгенерировать
     * (0/отсутствие = все); существующие пары скипаются (заморозка кэша), limit не расходуют.
     */
    public static void run(EvalConfig config, int limit) throws Exception {
        if (config.llmApiKey().isBlank()) {
            throw new IllegalStateException("llm.api-key required: arms без LLM бессмысленны");
        }
        LlmClient client = new LlmClient(
                config.llmAnswerBaseUrl().isBlank() ? config.llmBaseUrl() : config.llmAnswerBaseUrl(),
                config.llmAnswerApiKey().isBlank() ? config.llmApiKey() : config.llmAnswerApiKey(),
                config.llmAnswerModel().isBlank() ? config.llmModel() : config.llmAnswerModel(),
                config.llmAnswerReasoningEffort());
        List<EvalPoint> points = EvalDataset.load(EvalPaths.resolve(config.datasetFile()));
        Path snapshotsDir = EvalPaths.evalDir().resolve("out/snapshots");
        Path answersDir = EvalPaths.evalDir().resolve("out/answers");

        int generated = 0;
        int cached = 0;
        int skipped = 0;
        for (EvalPoint point : points) {
            if (limit > 0 && generated >= limit) {
                break;
            }
            Path aFile = answersDir.resolve(point.id() + ".a.json");
            Path bFile = answersDir.resolve(point.id() + ".b.json");
            if (Files.exists(aFile) && Files.exists(bFile)) {
                cached++;
                continue;
            }
            Path snapshotFile = snapshotsDir.resolve(point.id() + ".json");
            if (!Files.exists(snapshotFile)) {
                skipped++;
                System.out.printf("[ARMS] point %s: no snapshot, skipped (run replay first)%n", point.id());
                continue;
            }
            List<RawMessage> log = readLog(config, point);
            int idxM = indexOf(log, point.sourceMessageId());
            if (idxM < 0) {
                skipped++;
                System.out.printf("[ARMS] point %s: message %d outside the log, skipped%n",
                        point.id(), point.sourceMessageId());
                continue;
            }
            long w0 = EvalRunner.windowStartId(log, idxM, config.windowSize());
            if (w0 < 0) {
                skipped++;
                System.out.printf("[ARMS] point %s: insufficient grid (no W0), skipped%n", point.id());
                continue;
            }
            List<RawMessage> window = windowMessages(log, w0, point.sourceMessageId());
            String digest = memoryDigest(Files.readString(snapshotFile));
            if (digest.isBlank()) {
                System.out.printf("[ARMS] point %s: snapshot is empty — arm A degrades to B%n", point.id());
            }
            String payload = userPayload(window, point.trigger());

            long t0 = System.currentTimeMillis();
            LlmClient.ChatResult a = client.chatWithUsage(systemPrompt(digest), payload, 0.0);
            long aLatency = System.currentTimeMillis() - t0;
            System.out.printf("[ARMS] point %s: arm A answered (%dms, %d tokens)%n",
                    point.id(), aLatency, a.usage().totalTokens());
            t0 = System.currentTimeMillis();
            LlmClient.ChatResult b = client.chatWithUsage(systemPrompt(null), payload, 0.0);
            long bLatency = System.currentTimeMillis() - t0;
            System.out.printf("[ARMS] point %s: arm B answered (%dms, %d tokens)%n",
                    point.id(), bLatency, b.usage().totalTokens());

            writeAnswer(aFile, a.content(), client.model(), aLatency, a.usage());
            writeAnswer(bFile, b.content(), client.model(), bLatency, b.usage());
            generated++;
        }
        System.out.printf("[ARMS] done: %d pair(s) generated, %d cached (files exist), %d skipped -> %s%n",
                generated, cached, skipped, answersDir.normalize());
    }

    private static void writeAnswer(Path file, String answer, String model,
                                    long latencyMs, LlmClient.Usage usage) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode node = MAPPER.createObjectNode();
        node.put("answer", answer);
        node.put("model", model);
        node.put("tokens", usage.totalTokens());
        node.put("promptTokens", usage.promptTokens());
        node.put("completionTokens", usage.completionTokens());
        node.put("latencyMs", latencyMs);
        node.put("cached", false);
        node.put("at", OffsetDateTime.now().toString());
        writeAtomically(file, MAPPER.writeValueAsString(node));
    }

    private static List<RawMessage> readLog(EvalConfig config, EvalPoint point) throws Exception {
        return EvalRunner.readLog(config, point.sourceSession());
    }

    private static int indexOf(List<RawMessage> log, long id) {
        for (int i = 0; i < log.size(); i++) {
            if (log.get(i).id() == id) {
                return i;
            }
        }
        return -1;
    }

    private static void writeAtomically(Path target, String content) throws Exception {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
