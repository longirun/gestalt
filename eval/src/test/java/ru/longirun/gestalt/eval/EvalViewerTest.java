package ru.longirun.gestalt.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;

/**
 * Рабочий стол разметчика E2 (план 31 §2.7): поднимает HttpServer со статикой и API,
 * разметчик работает в браузере. Стоп теста в IDE = стоп сервера. Тег "viewer" исключён
 * из дефолтного gradlew test — запускать только явно из IDE.
 * Все GET читают файлы с диска на каждый запрос: правки jsonl/новые слепки подхватываются F5.
 */
@Tag("viewer")
class EvalViewerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Set<String> REJECT_REASONS = Set.of("poor-log", "encoding", "noise");

    @Test
    void markingWorkbench() throws Exception {
        EvalConfig config = EvalConfig.load(EvalPaths.resolve("eval/local.properties"));
        Path dataDir = EvalPaths.evalDir().resolve("data");
        Path snapshotsDir = EvalPaths.evalDir().resolve("out/snapshots");
        Path pointsFile = EvalPaths.resolve(config.datasetFile());
        Path journalFile = dataDir.resolve("marking-journal.jsonl");

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", ex -> {
            try {
                route(ex, dataDir, snapshotsDir, pointsFile, journalFile);
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
                       Path pointsFile, Path journalFile) throws Exception {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        if ("GET".equals(method) && "/".equals(path)) {
            serveIndex(ex);
        } else if ("GET".equals(method) && "/api/day".equals(path)) {
            sendFileAsJsonl(ex, dataDir.resolve("candidates.jsonl"));
        } else if ("GET".equals(method) && "/api/points".equals(path)) {
            sendFileAsJsonl(ex, pointsFile);
        } else if ("GET".equals(method) && "/api/journal".equals(path)) {
            sendFileAsJsonl(ex, journalFile);
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

    /** Журнал разметки (план 31 §2.8): candidateId, verdict taken|rejected, reason, pointId, at. */
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
        com.fasterxml.jackson.databind.node.ObjectNode entry = MAPPER.createObjectNode();
        entry.put("candidateId", candidateId);
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
