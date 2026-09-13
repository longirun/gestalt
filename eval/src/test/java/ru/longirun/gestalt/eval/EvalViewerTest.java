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
import ru.longirun.gestalt.eval.store.ExperimentStore;
import ru.longirun.gestalt.eval.store.PointSnapshotStore;
import ru.longirun.gestalt.eval.store.ResultStore;

/**
 * Рабочий стол разметчика E2 (план 31 §2.7): поднимает HttpServer со статикой и API,
 * разметчик работает в браузере. Стоп теста в IDE = стоп сервера. Тег "viewer" исключён
 * из дефолтного gradlew test — запускать только явно из IDE.
 * Входы (файлы, вне VCS): точки датасета, журнал разметки; слепки — файловый кэш out/snapshots.
 * Результаты прибора (§7): answers/verdicts/wchecks — из gestalt_eval, эксперимент выбирается
 * ?exp=<slug> (по умолчанию единственный active). День (/api/day): LME-точки получают лог из
 * LongMemEval-файла, live-точки — из data/candidates.jsonl; формат строк одинаков.
 * Экран — точка-центричный (план 31 §2.7): досье точки (таймлайн, why, coverage, истина, A/B).
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
        String exp = queryParam(ex, "exp");
        Path activePointsFile = resolvePointsFile(config, pointsFile, exp);
        Path activeJournalFile = resolveJournalFile(dataDir, journalFile, config, exp);
        if ("GET".equals(method) && "/".equals(path)) {
            serveIndex(ex);
        } else if ("GET".equals(method) && "/api/day".equals(path)) {
            sendDay(ex, dataDir, activePointsFile, config, exp);
        } else if ("GET".equals(method) && "/api/prod-facts".equals(path)) {
            sendProdFacts(ex, dataDir, activePointsFile, config, prodDb, exp);
        } else if ("GET".equals(method) && "/api/points".equals(path)) {
            sendFileAsJsonl(ex, activePointsFile);
        } else if ("GET".equals(method) && "/api/journal".equals(path)) {
            sendFileAsJsonl(ex, activeJournalFile);
        } else if ("GET".equals(method) && "/api/experiments".equals(path)) {
            sendExperiments(ex, config, exp);
        } else if ("GET".equals(method) && "/api/wcheck".equals(path)) {
            sendLifecycleJsonl(ex, config, exp, "wchecks");
        } else if ("GET".equals(method) && "/api/answers".equals(path)) {
            sendAnswers(ex, config, exp);
        } else if ("GET".equals(method) && "/api/oracles".equals(path)) {
            sendLifecycleJsonl(ex, config, exp, "verdicts");
        } else if ("GET".equals(method) && "/api/snapshots".equals(path)) {
            sendSnapshotList(ex, snapshotsDir, config, exp);
        } else if ("GET".equals(method) && path.startsWith("/api/snapshot/")) {
            sendSnapshot(ex, snapshotsDir, path.substring("/api/snapshot/".length()), config, exp);
        } else if ("POST".equals(method) && "/api/point".equals(path)) {
            appendPoint(ex, activePointsFile);
        } else if ("POST".equals(method) && "/api/journal".equals(path)) {
            appendJournal(ex, activeJournalFile);
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
    private void sendDay(HttpExchange ex, Path dataDir, Path pointsFile, EvalConfig config, String requestedExp) throws IOException {
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
            String experiment = resolveExperimentOrNull(config, requestedExp);
            Path candidates = null;
            if (experiment != null) {
                Path archiveCandidates = dataDir.resolve("archive/candidates." + experiment + ".jsonl");
                if (Files.exists(archiveCandidates)) {
                    candidates = archiveCandidates;
                }
            }
            if (candidates == null) {
                candidates = dataDir.resolve("candidates.jsonl");
            }
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

    private void sendSnapshotList(HttpExchange ex, Path snapshotsDir, EvalConfig config, String requested)
            throws IOException {
        String experiment = resolveExperimentOrNull(config, requested);
        if (experiment != null) {
            try (Connection c = DriverManager.getConnection(
                    config.targetDbUrl(), config.targetDbUser(), config.targetDbPassword())) {
                List<String> ids = new PointSnapshotStore(c).list(experiment).stream()
                        .map(ref -> ref.pointId() + ("w0".equals(ref.kind()) ? ".w0" : ""))
                        .toList();
                if (!ids.isEmpty()) {
                    respond(ex, 200, MAPPER.writeValueAsString(ids));
                    return;
                }
            } catch (Exception ignore) {
            }
        }
        List<String> ids = new ArrayList<>();
        if (Files.isDirectory(snapshotsDir)) {
            try (var stream = Files.list(snapshotsDir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .forEach(p -> ids.add(p.getFileName().toString().replaceFirst("\\.json$", "")));
            }
        }
        respond(ex, 200, MAPPER.writeValueAsString(ids));
    }

    private void sendSnapshot(HttpExchange ex, Path snapshotsDir, String id, EvalConfig config, String requested)
            throws IOException {
        if (!SAFE_ID.matcher(id).matches()) {
            respond(ex, 400, "{\"error\":\"bad id\"}");
            return;
        }
        String experiment = resolveExperimentOrNull(config, requested);
        if (experiment != null) {
            try (Connection c = DriverManager.getConnection(
                    config.targetDbUrl(), config.targetDbUser(), config.targetDbPassword())) {
                String kind = id.endsWith(".w0") ? "w0" : "m";
                String pointId = id.endsWith(".w0") ? id.substring(0, id.length() - 3) : id;
                var snap = new PointSnapshotStore(c).find(experiment, pointId, kind);
                if (snap.isPresent()) {
                    respond(ex, 200, snap.get());
                    return;
                }
            } catch (Exception ignore) {
            }
        }
        Path file = snapshotsDir.resolve(id + ".json");
        if (!Files.exists(file)) {
            respond(ex, 404, "{\"error\":\"no snapshot for " + escape(id) + ": run replay\"}");
            return;
        }
        respond(ex, 200, Files.readString(file));
    }

    /** Реестр экспериментов (§7): список + диагностика выбора (?exp= или единственный active). */
    private void sendExperiments(HttpExchange ex, EvalConfig config, String requested) throws IOException {
        com.fasterxml.jackson.databind.node.ObjectNode root = MAPPER.createObjectNode();
        try (Connection c = DriverManager.getConnection(
                config.targetDbUrl(), config.targetDbUser(), config.targetDbPassword())) {
            ExperimentStore store = new ExperimentStore(c);
            com.fasterxml.jackson.databind.node.ArrayNode experiments = root.putArray("experiments");
            for (ExperimentStore.Experiment e : store.list()) {
                com.fasterxml.jackson.databind.node.ObjectNode node = experiments.addObject();
                node.put("slug", e.slug());
                node.put("material", e.material());
                node.put("status", e.status());
                node.put("datasetRef", e.datasetRef());
                node.put("note", e.note());
                node.put("active", e.status().equals("active"));
            }
            try {
                root.put("selected", Experiments.resolveActive(store, requested));
            } catch (Exception e) {
                root.put("error", e.getMessage());
            }
            respond(ex, 200, MAPPER.writeValueAsString(root));
        } catch (Exception e) {
            root.put("error", "gestalt_eval: " + e.getMessage());
            respond(ex, 200, MAPPER.writeValueAsString(root));
        }
    }

    /** Эксперимент выборки: ?exp=<slug>, иначе единственный active; неразрешим → null (пустые данные,
     *  диагностика — /api/experiments). Р3: PG — источник истины результатов прибора. */
    private String resolveExperimentOrNull(EvalConfig config, String requested) {
        try (Connection c = DriverManager.getConnection(
                config.targetDbUrl(), config.targetDbUser(), config.targetDbPassword())) {
            return Experiments.resolveActive(new ExperimentStore(c), requested);
        } catch (Exception e) {
            return null;
        }
    }

    /** ?exp=<slug> из query string (null, если нет). */
    private static String queryParam(HttpExchange ex, String name) {
        String query = ex.getRequestURI().getRawQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (name.equals(key)) {
                return eq < 0 ? "" : java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /** verdicts|wchecks эксперимента → jsonl (контракт прежних файлов, фронт не меняется). */
    private void sendLifecycleJsonl(HttpExchange ex, EvalConfig config, String requested, String table)
            throws IOException {
        String experiment = resolveExperimentOrNull(config, requested);
        if (experiment == null) {
            respond(ex, 200, "");
            return;
        }
        try (Connection c = DriverManager.getConnection(
                config.targetDbUrl(), config.targetDbUser(), config.targetDbPassword())) {
            ResultStore results = new ResultStore(c);
            List<String> payloads = table.equals("verdicts")
                    ? results.verdicts(experiment).stream().map(ResultStore.VerdictRow::payloadJson).toList()
                    : results.wchecks(experiment).stream().map(ResultStore.WCheckRow::payloadJson).toList();
            respond(ex, 200, String.join("\n", payloads));
        } catch (Exception e) {
            respond(ex, 200, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        }
    }

    /** Ответы плеч (E3) из gestalt_eval (§7): агрегат {pointId: {a:…, b:…}}, формат полей — как в answers. */
    private void sendAnswers(HttpExchange ex, EvalConfig config, String requested) throws IOException {
        com.fasterxml.jackson.databind.node.ObjectNode root = MAPPER.createObjectNode();
        String experiment = resolveExperimentOrNull(config, requested);
        if (experiment == null) {
            respond(ex, 200, MAPPER.writeValueAsString(root));
            return;
        }
        try (Connection c = DriverManager.getConnection(
                config.targetDbUrl(), config.targetDbUser(), config.targetDbPassword())) {
            for (ResultStore.AnswerRow row : new ResultStore(c).answers(experiment)) {
                com.fasterxml.jackson.databind.node.ObjectNode node = MAPPER.createObjectNode();
                node.put("answer", row.answer());
                node.put("model", row.model());
                node.put("tokens", row.tokens());
                node.put("promptTokens", row.promptTokens());
                node.put("completionTokens", row.completionTokens());
                node.put("latencyMs", row.latencyMs());
                node.put("cached", row.cached());
                node.put("at", row.at() == null ? null : row.at().toString());
                root.withObject(row.pointId()).set(row.arm(), node);
            }
            respond(ex, 200, MAPPER.writeValueAsString(root));
        } catch (Exception e) {
            root.put("error", "gestalt_eval: " + e.getMessage());
            respond(ex, 200, MAPPER.writeValueAsString(root));
        }
    }

    /** Выводы, уже лежащие в production-базе фактов: viewer показывает их с evidence — из каких
     *  реплик среза выведен каждый факт (план 31 §2.7, read-only). Для чистого LME-датасета
     *  секция не релевантна: микромиры LongMemEval в production не инжестятся. */
    private void sendProdFacts(HttpExchange ex, Path dataDir, Path pointsFile, EvalConfig config,
                               DbCreds prodDb, String requestedExp) throws IOException {
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
        String experiment = resolveExperimentOrNull(config, requestedExp);
        Path dayFile = null;
        if (experiment != null) {
            Path archiveCandidates = dataDir.resolve("archive/candidates." + experiment + ".jsonl");
            if (Files.exists(archiveCandidates)) {
                dayFile = archiveCandidates;
            }
        }
        if (dayFile == null) {
            dayFile = dataDir.resolve("candidates.jsonl");
        }
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

    private Path resolvePointsFile(EvalConfig config, Path defaultPointsFile, String requestedExp) {
        String experiment = resolveExperimentOrNull(config, requestedExp);
        if (experiment != null) {
            try (Connection c = DriverManager.getConnection(
                    config.targetDbUrl(), config.targetDbUser(), config.targetDbPassword())) {
                var exp = new ExperimentStore(c).find(experiment);
                if (exp.isPresent() && exp.get().datasetRef() != null && !exp.get().datasetRef().isBlank()) {
                    Path resolved = EvalPaths.resolve(exp.get().datasetRef());
                    if (Files.exists(resolved)) {
                        return resolved;
                    }
                }
            } catch (Exception ignore) {
            }
        }
        return defaultPointsFile;
    }

    private Path resolveJournalFile(Path dataDir, Path defaultJournalFile, EvalConfig config, String requestedExp) {
        String experiment = resolveExperimentOrNull(config, requestedExp);
        if (experiment != null) {
            Path archiveJournal = dataDir.resolve("archive/marking-journal." + experiment + ".jsonl");
            if (Files.exists(archiveJournal)) {
                return archiveJournal;
            }
        }
        return defaultJournalFile;
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
