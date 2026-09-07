package ru.longirun.gestalt.spike.store;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Scanner;

/** Применяет sql/001_schema.sql, sql/002_dynamics.sql к целевой базе (gestalt_spike). */
public final class SchemaMigrator {

    public static void migrate(Connection connection) throws Exception {
        apply(connection, "/sql/001_schema.sql");
        apply(connection, "/sql/002_dynamics.sql");
    }

    private static void apply(Connection connection, String resource) throws Exception {
        try (var in = SchemaMigrator.class.getResourceAsStream(resource);
             Statement statement = connection.createStatement();
             Scanner scanner = new Scanner(in, StandardCharsets.UTF_8)) {
            scanner.useDelimiter("\\A");
            if (!scanner.hasNext()) {
                return;
            }
            String fullSql = scanner.next();
            // Убираем однострочные комментарии (-- ...): точка с запятой внутри комментария ломает сплит
            String cleanedSql = fullSql.replaceAll("(?m)--.*$", "");
            for (String part : cleanedSql.split(";")) {
                String sql = part.strip();
                if (sql.isEmpty()) {
                    continue;
                }

                try {
                    statement.execute(sql);
                } catch (Exception e) {
                    String lowerSql = sql.toLowerCase();
                    if (lowerSql.contains("extension") && lowerSql.contains("vector")) {
                        System.err.println("[WARN] pgvector extension not available, continuing without vectors: " + e.getMessage());
                    } else if (lowerSql.contains("facts") && e.getMessage().contains("vector")) {
                        // Fallback: без pgvector создаём facts без векторной колонки
                        String fallbackSql = sql.replaceAll("(?i)embedding\\s+vector\\(\\d+\\),?", "");
                        statement.execute(fallbackSql);
                    } else {
                        throw e;
                    }
                }
            }
        }
    }

    private SchemaMigrator() {
    }
}
