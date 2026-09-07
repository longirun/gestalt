package ru.longirun.gestalt.spike.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.longirun.gestalt.spike.dedup.ExactMatcher;
import ru.longirun.gestalt.spike.extract.ExtractedFact;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Pure JDBC репозиторий для таблицы facts (канон 16 §2.1-2.3).
 * Вставка факта, exact-поиск кандидата, reinforcement_count++, выборка для слепка.
 */
public final class FactRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FACT_COLUMNS =
            "id, owner_id, session_id, project_id, fact_domain, scope, kind, "
            + "subject_norm, predicate_norm, object_value, statement, "
            + "conditions, reinforcement_count, w, created_at";
    private final Connection connection;

    public record StoredFact(
            UUID id,
            String ownerId,
            String sessionId,
            String projectId,
            String domain,
            String scope,
            String kind,
            String subjectNorm,
            String predicateNorm,
            String objectValue,
            String statement,
            Map<String, String> conditions,
            int reinforcementCount,
            double w,
            OffsetDateTime createdAt) {
    }

    public FactRepository(Connection connection) {
        this.connection = connection;
    }

    public UUID insert(String ownerId, String sessionId, String projectId, ExtractedFact fact) throws SQLException {
        String sql = """
                INSERT INTO facts (
                    id, owner_id, session_id, project_id,
                    fact_domain, scope, kind,
                    subject_norm, predicate_norm, object_value,
                    statement, conditions, evidence,
                    reinforcement_count, permanence, w
                ) VALUES (
                    ?, ?, ?, ?,
                    ?, ?, ?,
                    ?, ?, ?,
                    ?, ?::jsonb, ?::jsonb,
                    1, ?, 1.0
                )
                """;

        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, id);
            ps.setString(2, ExactMatcher.normalize(ownerId));
            ps.setString(3, sessionId);
            if (projectId != null && !projectId.isBlank()) {
                ps.setString(4, ExactMatcher.normalize(projectId));
            } else {
                ps.setNull(4, Types.VARCHAR);
            }
            ps.setString(5, fact.domain());
            ps.setString(6, fact.scope());
            ps.setString(7, fact.kind());
            ps.setString(8, ExactMatcher.normalize(fact.subject()));
            ps.setString(9, ExactMatcher.normalize(fact.predicate()));
            ps.setString(10, fact.object());
            ps.setString(11, fact.statement());
            ps.setString(12, toJson(fact.conditions()));
            ps.setString(13, toJson(fact.evidenceMessageIds()));
            ps.setString(14, "USER_ASSERTED");

            ps.executeUpdate();
            return id;
        }
    }

    public Optional<StoredFact> findExactCandidate(
            String ownerId,
            String scope,
            String projectId,
            String subjectNorm,
            String predicateNorm,
            Map<String, String> conditions) throws SQLException {

        String normOwner = ExactMatcher.normalize(ownerId);
        String normScope = scope != null ? scope.trim().toUpperCase() : "PROJECT";
        String normProject = projectId != null && !projectId.isBlank() ? ExactMatcher.normalize(projectId) : null;

        String normSubject = ExactMatcher.normalize(subjectNorm);
        String normPredicate = ExactMatcher.normalize(predicateNorm);
        String condJson = toJson(conditions);

        String sql = (normProject == null)
                ? """
                SELECT %s
                FROM facts
                WHERE owner_id = ?
                  AND scope = ?
                  AND project_id IS NULL
                  AND subject_norm = ?
                  AND predicate_norm = ?
                  AND conditions = ?::jsonb
                LIMIT 1
                """.formatted(FACT_COLUMNS)
                : """
                SELECT %s
                FROM facts
                WHERE owner_id = ?
                  AND scope = ?
                  AND project_id = ?
                  AND subject_norm = ?
                  AND predicate_norm = ?
                  AND conditions = ?::jsonb
                LIMIT 1
                """.formatted(FACT_COLUMNS);

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, normOwner);
            ps.setString(2, normScope);
            if (normProject == null) {
                ps.setString(3, normSubject);
                ps.setString(4, normPredicate);
                ps.setString(5, condJson);
            } else {
                ps.setString(3, normProject);
                ps.setString(4, normSubject);
                ps.setString(5, normPredicate);
                ps.setString(6, condJson);
            }

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    public void incrementReinforcement(UUID factId, List<Long> newEvidenceIds) throws SQLException {
        String sql = """
                UPDATE facts
                SET reinforcement_count = reinforcement_count + 1,
                    evidence = CASE
                        WHEN ?::jsonb IS NULL OR jsonb_array_length(?::jsonb) = 0 THEN evidence
                        ELSE evidence || ?::jsonb
                    END
                WHERE id = ?
                """;
        String evJson = toJson(newEvidenceIds != null ? newEvidenceIds : List.of());
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, evJson);
            ps.setString(2, evJson);
            ps.setString(3, evJson);
            ps.setObject(4, factId);
            ps.executeUpdate();
        }
    }

    public List<StoredFact> findByOwnerAndProject(String ownerId, String projectId) throws SQLException {
        String sql = """
                SELECT %s
                FROM facts
                WHERE owner_id = ?
                  AND (
                    scope = 'USER'
                    OR project_id = ?
                    OR project_id IS NULL
                  )
                ORDER BY created_at ASC
                """.formatted(FACT_COLUMNS);

        String normOwner = ExactMatcher.normalize(ownerId);
        String normProject = projectId != null && !projectId.isBlank() ? ExactMatcher.normalize(projectId) : null;

        List<StoredFact> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, normOwner);
            ps.setString(2, normProject);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    public int countFacts() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT count(*) FROM facts");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private StoredFact mapRow(ResultSet rs) throws SQLException {
        return new StoredFact(
                rs.getObject("id", UUID.class),
                rs.getString("owner_id"),
                rs.getString("session_id"),
                rs.getString("project_id"),
                rs.getString("fact_domain"),
                rs.getString("scope"),
                rs.getString("kind"),
                rs.getString("subject_norm"),
                rs.getString("predicate_norm"),
                rs.getString("object_value"),
                rs.getString("statement"),
                fromJsonMap(rs.getString("conditions")),
                rs.getInt("reinforcement_count"),
                rs.getDouble("w"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private static String toJson(Object obj) {
        if (obj == null) {
            return "{}";
        }
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize to JSON: " + obj, e);
        }
    }

    private static Map<String, String> fromJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
