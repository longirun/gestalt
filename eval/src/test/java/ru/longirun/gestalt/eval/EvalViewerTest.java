package ru.longirun.gestalt.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;
import ru.longirun.gestalt.eval.ingest.LongMemEvalAdapter;
import ru.longirun.gestalt.eval.ingest.RawMessage;

/**
 * Рабочий стол разметчика E2 (план 31 §2.7): поднимает HttpServer со статикой и API,
 * разметчик работает в браузере. Стоп теста в IDE = стоп сервера. Тег "viewer" исключён
 * из дефолтного gradlew test — запускать только явно из IDE.
 * Все GET читают файлы с диска на каждый запрос: правки jsonl/новые слепки подхватываются F5.
 * День (/api/day): LME-точки получают лог из LongMemEval-файла (нумерация реплик та же, что у
 * replay/wcheck), live-точки — из data/candidates.jsonl; формат строк одинаков.
 * Экран — точка-центричный (план 31 §2.7): досье точки (таймлайн, why, coverage, истина, A/B);
 * данные плеч E3/E4 — GET /api/answers, /api/oracles, читаются из out/ и рендерятся по мере появления.
 */
@Tag("viewer")
class EvalViewerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Set<String> REJECT_REASONS =
            Set.of("poor-log", "no-fork", "in-window", "encoding", "noise");

    private record DbCreds(String url, String user, String password) {
    }

    @Test
    void markingWorkbench() throws Exception {
        Path propsFile = EvalPaths.resolve("eval/local.properties");
        EvalConfig config = EvalConfig.load(propsFile);
        Path dataDir = EvalPaths.evalDir().resolve("data");
        Path snapshotsDir = EvalPaths.evalDir().resolve("out/snapshots");
        Path pointsFile = EvalPaths.resolve(config.datasetFile());
        Path journalFile = dataDir.resolve("marking-journal.jsonl");
        DbCreds prodDb = prodDbCreds(propsFile);

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", ex -> {
            try {
                route(ex, dataDir, snapshotsDir, pointsFile, journalFile, config, prodDb);
            } catch (Exception e) {
                respond(ex, 500, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
            }
        });
        server.start();
        String url = "http://localhost:" + server.getAddress().getPort() + "/";
        System.out.println();
        System.out.println("[VIEWER] " + url);
        System.out.println("[VIEWER] points: " + pointsFile.normalize()
                + ", journal: " + journalFile.normalize());
        System.out.println("[VIEWER] Stop this test (IDE) to shut the server down.");
        new CountDownLatch(1).await();
    }

    private void route(HttpExchange ex, Path dataDir, Path snapshotsDir,
                       Path pointsFile, Path journalFile, EvalConfig config, DbCreds prodDb) throws Exception {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        if ("GET".equals(method) && "/".equals(path)) {
            serveIndex(ex);
        } else if ("GET".equals(method) && "/api/day".equals(path)) {
            sendDay(ex, dataDir, pointsFile, config);
        } else if ("GET".equals(method) && "/api/prod-facts".equals(path)) {
            sendProdFacts(ex, dataDir, pointsFile, config, prodDb);
        } else if ("GET".equals(method) && "/api/points".equals(path)) {
            sendFileAsJsonl(ex, pointsFile);
        } else if ("GET".equals(method) && "/api/journal".equals(path)) {
            sendFileAsJsonl(ex, journalFile);
        } else if ("GET".equals(method) && "/api/wcheck".equals(path)) {
            sendFileAsJsonl(ex, dataDir.getParent().resolve("out/wcheck.jsonl"));
        } else if ("GET".equals(method) && "/api/answers".equals(path)) {
            sendAnswers(ex, dataDir.getParent().resolve("out/answers"));
        } else if ("GET".equals(method) && "/api/oracles".equals(path)) {
            sendFileAsJsonl(ex, dataDir.getParent().resolve("out/oracles.jsonl"));
        } else if ("GET".equals(method) && "/api/snapshots".equals(path)) {
            sendSnapshotList(ex, snapshotsDir);
        } else if ("GET".equals(method) && path.startsWith("/api/snapshot/")) {
            sendSnapshot(ex, snapshotsDir, path.substring("/api/snapshot/".length()));
        } else if ("POST".equals(method) && "/api/point".equals(path)) {
            appendPoint(ex, pointsFile);
        } else if ("POST".equals(method) && "/api/journal".equals(path)) {
            appendJournal(ex, journalFile);
        } else {
            respond(ex, 404, "{\"error\":\"not found\"}");
        }
    }

    private void serveIndex(HttpExchange ex) throws IOException {
        try (var in = getClass().getResourceAsStream("/viewer/index.html")) {
            if (in == null) {
                respond(ex, 500, "viewer/index.html not found in test resources");
                return;
            }
            byte[] body = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        }
    }

    private void sendFileAsJsonl(HttpExchange ex, Path file) throws IOException {
        String body = Files.exists(file) ? Files.readString(file) : "";
        respond(ex, 200, body);
    }

    /** Разделение точек датасета: LME-сессии (день из LongMemEval-файла) и live (candidates.jsonl). */
    private record DatasetKinds(Set<String> lmeSessions, boolean hasLive) {
    }

    private DatasetKinds datasetKinds(Path pointsFile, EvalConfig config) throws IOException {
        Set<String> lmeSessions = new LinkedHashSet<>();
        boolean hasLive = false;
        if (Files.exists(pointsFile)) {
            for (String line : Files.readAllLines(pointsFile)) {
                if (line.isBlank()) {
                    continue;
                }
                String session = MAPPER.readTree(line).path("sourceSession").asText();
                if (EvalRunner.isLme(config, session)) {
                    lmeSessions.add(session);
                } else {
                    hasLive = true;
                }
            }
        }
        return new DatasetKinds(lmeSessions, hasLive);
    }

    /** День viewer'а: LME-точки — лог из LongMemEval-файла одним проходом (идентичная нумерация
     *  replay/wcheck — контракт LongMemEvalAdapter.buildLog), live-точки — data/candidates.jsonl. */
    private void sendDay(HttpExchange ex, Path dataDir, Path pointsFile, EvalConfig config) throws IOException {
        DatasetKinds kinds = datasetKinds(pointsFile, config);
        List<String> lines = new ArrayList<>();
        if (!kinds.lmeSessions().isEmpty()) {
            Set<String> questionIds = new HashSet<>();
            for (String session : kinds.lmeSessions()) {
                questionIds.add(session.substring(LongMemEvalAdapter.SESSION_PREFIX.length()));
            }
            LongMemEvalAdapter adapter = new LongMemEvalAdapter(EvalPaths.resolve(config.sourceLmeFile()));
            adapter.forEachRecord(record -> {
                if (questionIds.contains(record.questionId())) {
                    for (RawMessage message : LongMemEvalAdapter.buildLog(record)) {
                        lines.add(toDayLine(message));
                    }
                }
            });
        }
        if (kinds.hasLive()) {
            Path candidates = dataDir.resolve("candidates.jsonl");
            if (Files.exists(candidates)) {
                lines.addAll(Files.readAllLines(candidates));
            }
        }
        respond(ex, 200, String.join("\n", lines));
    }

    private String toDayLine(RawMessage m) {
        com.fasterxml.jackson.databind.node.ObjectNode n = MAPPER.createObjectNode();
        n.put("id", m.id());
        n.put("session", m.sessionName());
        n.put("peer", m.peerName());
        n.put("tokens", m.tokenCount());
        n.put("time", m.createdAt() == null ? "" : m.createdAt().toString());
        n.put("content", m.content());
        try {
            return MAPPER.writeValueAsString(n);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void sendSnapshotList(HttpExchange ex, Path snapshotsDir) throws IOException {
        List<String> ids = new ArrayList<>();
        if (Files.isDirectory(snapshotsDir)) {
            try (var stream = Files.list(snapshotsDir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .forEach(p -> ids.add(p.getFileName().toString().replaceFirst("\\.json$", "")));
            }
        }
        respond(ex, 200, MAPPER.writeValueAsString(ids));
    }

    private void sendSnapshot(HttpExchange ex, Path snapshotsDir, String id) throws IOException {
        if (!SAFE_ID.matcher(id).matches()) {
            respond(ex, 400, "{\"error\":\"bad id\"}");
            return;
        }
        Path file = snapshotsDir.resolve(id + ".json");
        if (!Files.exists(file)) {
            respond(ex, 404, "{\"error\":\"no snapshot for " + escape(id) + ": run replay\"}");
            return;
        }
        respond(ex, 200, Files.readString(file));
    }

    /** Ответы плеч (стадия E3): out/answers/<pointId>.{a,b}.json → агрегат {pointId: {a:…, b:…}}.
     *  Битый файл одного плеча не роняет весь список — A/B ещё не прогонялись или пишутся частями. */
    private void sendAnswers(HttpExchange ex, Path answersDir) throws IOException {
        com.fasterxml.jackson.databind.node.ObjectNode root = MAPPER.createObjectNode();
        if (Files.isDirectory(answersDir)) {
            try (var stream = Files.list(answersDir)) {
                for (Path f : stream.filter(p -> {
                            String n = p.getFileName().toString();
                            return n.endsWith(".a.json") || n.endsWith(".b.json");
                        }).sorted().toList()) {
                    String name = f.getFileName().toString().replaceFirst("\\.json$", "");
                    String pointId = name.substring(0, name.length() - 2);
                    String arm = name.substring(name.length() - 1);
                    try {
                        root.withObject(pointId).set(arm, MAPPER.readTree(Files.readString(f)));
                    } catch (Exception ignore) {
                        // повреждённый json ответа — пропускаем плечо, остальное показываем
                    }
                }
            }
        }
        respond(ex, 200, MAPPER.writeValueAsString(root));
    }

    /** Выводы, уже лежащие в production-базе фактов: viewer показывает их с evidence — из каких
     *  реплик среза выведен каждый факт (план 31 §2.7, read-only). Для чистого LME-датасета
     *  секция не релевантна: микромиры LongMemEval в production не инжестятся. */
    private void sendProdFacts(HttpExchange ex, Path dataDir, Path pointsFile, EvalConfig config,
                               DbCreds prodDb) throws IOException {
        if (prodDb.url().isBlank()) {
            respond(ex, 200, "{\"facts\":[],\"error\":\"prod.db.* not configured (eval/local.properties)\"}");
            return;
        }
        DatasetKinds kinds = datasetKinds(pointsFile, config);
        if (!kinds.lmeSessions().isEmpty() && !kinds.hasLive()) {
            respond(ex, 200, "{\"facts\":[],\"lme\":true," +
                    "\"error\":\"LME-датасет — production-база не релевантна (микромиры LongMemEval)\"}");
            return;
        }
        List<Long> ids = new ArrayList<>();
        Path dayFile = dataDir.resolve("candidates.jsonl");
        if (Files.exists(dayFile)) {
            for (String line : Files.readAllLines(dayFile)) {
                if (!line.isBlank()) {
                    ids.add(MAPPER.readTree(line).path("id").asLong());
                }
            }
        }
        com.fasterxml.jackson.databind.node.ObjectNode root = MAPPER.createObjectNode();
        com.fasterxml.jackson.databind.node.ArrayNode facts = root.putArray("facts");
        if (ids.isEmpty()) {
            respond(ex, 200, MAPPER.writeValueAsString(root));
            return;
        }
        String sql = """
                SELECT id, subject_norm, predicate_norm, object_value, statement,
                       reinforcement_count, evidence, created_at
                FROM facts
                WHERE EXISTS (
                    SELECT 1 FROM jsonb_array_elements_text(evidence) AS e(msg_id)
                    WHERE e.msg_id::bigint = ANY(?)
                )
                ORDER BY created_at DESC
                """;
        try (Connection c = DriverManager.getConnection(prodDb.url(), prodDb.user(), prodDb.password());
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setArray(1, c.createArrayOf("bigint", ids.toArray(new Long[0])));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    com.fasterxml.jackson.databind.node.ObjectNode f = facts.addObject();
                    f.put("id", rs.getObject("id", UUID.class).toString());
                    f.put("subject", rs.getString("subject_norm"));
                    f.put("predicate", rs.getString("predicate_norm"));
                    f.put("object", rs.getString("object_value"));
                    f.put("statement", rs.getString("statement"));
                    f.put("reinforcement", rs.getInt("reinforcement_count"));
                    com.fasterxml.jackson.databind.node.ArrayNode ev = f.putArray("evidence");
                    for (Long evId : fromJsonLongList(rs.getString("evidence"))) {
                        ev.add(evId);
                    }
                    OffsetDateTime created = rs.getObject("created_at", OffsetDateTime.class);
                    if (created != null) {
                        f.put("created", created.toLocalDate().toString());
                    }
                }
            }
            respond(ex, 200, MAPPER.writeValueAsString(root));
        } catch (Exception e) {
            root.put("error", "production DB: " + e.getMessage());
            respond(ex, 200, MAPPER.writeValueAsString(root));
        }
    }

    private static List<Long> fromJsonLongList(String json) {
        List<Long> list = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return list;
        }
        try {
            return MAPPER.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<Long>>() {});
        } catch (Exception e) {
            return list;
        }
    }

    private DbCreds prodDbCreds(Path propsFile) throws IOException {
        Properties props = new Properties();
        if (Files.exists(propsFile)) {
            try (var in = Files.newInputStream(propsFile)) {
                props.load(in);
            }
        }
        return new DbCreds(
                props.getProperty("prod.db.url", ""),
                props.getProperty("prod.db.user", ""),
                props.getProperty("prod.db.password", ""));
    }

    /** Append черновика/точки в points-file. Правка существующих — руками в IDE (план 31 §2.7). */
    private void appendPoint(HttpExchange ex, Path pointsFile) throws IOException {
        JsonNode body = readBody(ex);
        String id = body.path("id").asText("").trim();
        if (id.isEmpty() || !SAFE_ID.matcher(id).matches()) {
            respond(ex, 400, "{\"error\":\"point id required [A-Za-z0-9._-]\"}");
            return;
        }
        String level = body.path("level").asText("");
        if (!"L1".equals(level) && !"C".equals(level)) {
            respond(ex, 400, "{\"error\":\"level must be L1 or C\"}");
            return;
        }
        long messageId = body.path("sourceMessageId").asLong(0);
        if (messageId <= 0) {
            respond(ex, 400, "{\"error\":\"sourceMessageId required\"}");
            return;
        }
        if (body.path("trigger").asText("").isBlank() || body.path("truth").asText("").isBlank()) {
            respond(ex, 400, "{\"error\":\"trigger and truth required\"}");
            return;
        }
        if ("C".equals(level) && body.path("mustNot").isEmpty()) {
            respond(ex, 400, "{\"error\":\"C-point requires mustNot leak markers (plan 31 §E2)\"}");
            return;
        }
        Set<String> existing = new HashSet<>();
        if (Files.exists(pointsFile)) {
            for (String line : Files.readAllLines(pointsFile)) {
                if (!line.isBlank()) {
                    existing.add(MAPPER.readTree(line).path("id").asText());
                }
            }
        }
        if (!existing.add(id)) {
            respond(ex, 409, "{\"error\":\"duplicate point id: " + escape(id) + "\"}");
            return;
        }
        JsonNode canonical = canonicalPoint(body);
        appendLine(pointsFile, MAPPER.writeValueAsString(canonical));
        respond(ex, 200, "{\"ok\":true,\"id\":\"" + escape(id) + "\"}");
    }

    /** Журнал разметки (план 31 §2.8): candidateId, session (LME-сессии нумеруют реплики каждая
     *  с единицы — идентификатор кандидата только в паре с сессией), verdict taken|rejected,
     *  reason, pointId, at. */
    private void appendJournal(HttpExchange ex, Path journalFile) throws IOException {
        JsonNode body = readBody(ex);
        long candidateId = body.path("candidateId").asLong(0);
        if (candidateId <= 0) {
            respond(ex, 400, "{\"error\":\"candidateId required\"}");
            return;
        }
        String verdict = body.path("verdict").asText("");
        if (!"taken".equals(verdict) && !"rejected".equals(verdict)) {
            respond(ex, 400, "{\"error\":\"verdict must be taken or rejected\"}");
            return;
        }
        String reason = body.path("reason").asText("");
        if ("rejected".equals(verdict) && !REJECT_REASONS.contains(reason)) {
            respond(ex, 400, "{\"error\":\"reject reason must be one of " + REJECT_REASONS + "\"}");
            return;
        }
        String session = body.path("session").asText("");
        if (!session.isEmpty() && !SAFE_ID.matcher(session).matches()) {
            respond(ex, 400, "{\"error\":\"bad session id\"}");
            return;
        }
        com.fasterxml.jackson.databind.node.ObjectNode entry = MAPPER.createObjectNode();
        entry.put("candidateId", candidateId);
        if (!session.isEmpty()) {
            entry.put("session", session);
        }
        entry.put("verdict", verdict);
        if (!reason.isEmpty()) {
            entry.put("reason", reason);
        }
        String pointId = body.path("pointId").asText("");
        if (!pointId.isEmpty()) {
            entry.put("pointId", pointId);
        }
        entry.put("at", OffsetDateTime.now().toString());
        appendLine(journalFile, MAPPER.writeValueAsString(entry));
        respond(ex, 200, "{\"ok\":true}");
    }

    private JsonNode canonicalPoint(JsonNode body) {
        com.fasterxml.jackson.databind.node.ObjectNode node = MAPPER.createObjectNode();
        node.put("id", body.path("id").asText());
        node.put("level", body.path("level").asText());
        node.put("sourceSession", body.path("sourceSession").asText());
        node.put("sourceMessageId", body.path("sourceMessageId").asLong());
        node.put("trigger", body.path("trigger").asText());
        node.put("truth", body.path("truth").asText());
        node.set("must", stringArray(body.path("must")));
        node.set("mustNot", stringArray(body.path("mustNot")));
        String pattern = body.path("pattern").asText("");
        node.put("pattern", pattern.isEmpty() ? null : pattern);
        String coverage = body.path("coverage").asText("");
        node.put("coverage", coverage.isEmpty() ? null : coverage);
        return node;
    }

    private com.fasterxml.jackson.databind.node.ArrayNode stringArray(JsonNode source) {
        com.fasterxml.jackson.databind.node.ArrayNode array = MAPPER.createArrayNode();
        if (source.isArray()) {
            source.forEach(item -> array.add(item.asText()));
        }
        return array;
    }

    private JsonNode readBody(HttpExchange ex) throws IOException {
        byte[] bytes = ex.getRequestBody().readAllBytes();
        if (bytes.length == 0) {
            throw new IllegalArgumentException("empty request body");
        }
        return MAPPER.readTree(new String(bytes, StandardCharsets.UTF_8));
    }

    private void appendLine(Path file, String line) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, line + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
