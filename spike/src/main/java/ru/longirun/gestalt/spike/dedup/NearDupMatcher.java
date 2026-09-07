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

    public record SplitIdentityCandidate(String subjectA, String subjectB, double similarity) {
    }

    private final double threshold;

    public NearDupMatcher() {
        this(0.6);
    }

    public NearDupMatcher(double threshold) {
        this.threshold = threshold;
    }

    public double threshold() {
        return threshold;
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

    public List<SplitIdentityCandidate> scanSplitIdentity(Connection connection, double threshold) {
        return scanDictionary(connection, "subject_norm", threshold);
    }

    public List<SplitIdentityCandidate> scanPredicateCandidates(Connection connection, double threshold) {
        return scanDictionary(connection, "predicate_norm", threshold);
    }

    public List<SplitIdentityCandidate> scanDictionary(Connection connection, String column, double threshold) {
        if (connection == null) {
            return List.of();
        }
        String col = "predicate_norm".equalsIgnoreCase(column) ? "predicate_norm" : "subject_norm";
        String sql = """
                SELECT a.%s AS col_a, b.%s AS col_b, similarity(a.%s, b.%s) AS sim
                FROM (SELECT DISTINCT %s FROM facts WHERE %s IS NOT NULL AND %s <> '') a
                CROSS JOIN (SELECT DISTINCT %s FROM facts WHERE %s IS NOT NULL AND %s <> '') b
                WHERE a.%s < b.%s
                  AND similarity(a.%s, b.%s) >= ?
                ORDER BY sim DESC
                """.formatted(col, col, col, col, col, col, col, col, col, col, col, col, col, col);

        List<SplitIdentityCandidate> results = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setDouble(1, threshold);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new SplitIdentityCandidate(
                            rs.getString("col_a"),
                            rs.getString("col_b"),
                            rs.getDouble("sim")));
                }
                return results;
            }
        } catch (SQLException e) {
            return scanDictionaryInMemory(connection, col, threshold);
        }
    }

    private List<SplitIdentityCandidate> scanDictionaryInMemory(Connection connection, String col, double threshold) {
        List<String> distinctValues = new ArrayList<>();
        String sql = "SELECT DISTINCT " + col + " FROM facts WHERE " + col + " IS NOT NULL AND " + col + " <> ''";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                distinctValues.add(rs.getString(1));
            }
        } catch (SQLException ignored) {
            return List.of();
        }

        List<SplitIdentityCandidate> results = new ArrayList<>();
        for (int i = 0; i < distinctValues.size(); i++) {
            for (int j = i + 1; j < distinctValues.size(); j++) {
                String a = distinctValues.get(i);
                String b = distinctValues.get(j);
                double sim = trigramSimilarity(a, b);
                if (sim >= threshold) {
                    results.add(new SplitIdentityCandidate(a, b, sim));
                }
            }
        }
        results.sort((x, y) -> Double.compare(y.similarity(), x.similarity()));
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
