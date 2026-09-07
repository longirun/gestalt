package ru.longirun.gestalt.spike.dedup;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Шаг 2 дедупа (Р25): trgm по subject_norm + пороги без LLM (Р6).
 * PG-запрос через pg_trgm или in-memory триграммы при отсутствии базы.
 */
public final class NearDupMatcher {

    public record NearCandidate(UUID factId, String subjectNorm, double similarity) {
    }

    private final double threshold;

    public NearDupMatcher() {
        this(0.6);
    }

    public NearDupMatcher(double threshold) {
        this.threshold = threshold;
    }

    public List<NearCandidate> findCandidates(Connection connection, String subject) {
        if (connection == null || subject == null || subject.isBlank()) {
            return List.of();
        }
        String norm = ExactMatcher.normalize(subject);
        String sql = """
                SELECT id, subject_norm, similarity(subject_norm, ?) AS sim
                FROM facts
                WHERE similarity(subject_norm, ?) >= ?
                ORDER BY sim DESC
                LIMIT 5
                """;

        List<NearCandidate> results = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, norm);
            ps.setString(2, norm);
            ps.setDouble(3, threshold);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new NearCandidate(
                            rs.getObject("id", UUID.class),
                            rs.getString("subject_norm"),
                            rs.getDouble("sim")));
                }
            }
        } catch (SQLException e) {
            // pg_trgm не установлен или ошибка соединения -> тихая деградация без кандидатов
            return List.of();
        }
        return results;
    }

    public static double trigramSimilarity(String a, String b) {
        String na = ExactMatcher.normalize(a);
        String nb = ExactMatcher.normalize(b);
        if (na.equals(nb)) {
            return 1.0;
        }
        Set<String> setA = trigrams(na);
        Set<String> setB = trigrams(nb);
        if (setA.isEmpty() || setB.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(setA);
        intersection.retainAll(setB);
        Set<String> union = new HashSet<>(setA);
        union.addAll(setB);
        return (double) intersection.size() / union.size();
    }

    private static Set<String> trigrams(String s) {
        String padded = "  " + s + " ";
        Set<String> trg = new HashSet<>();
        for (int i = 0; i < padded.length() - 2; i++) {
            trg.add(padded.substring(i, i + 3));
        }
        return trg;
    }
}
