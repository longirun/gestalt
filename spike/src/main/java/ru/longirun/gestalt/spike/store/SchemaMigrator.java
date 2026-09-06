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
            for (String part : scanner.next().split(";")) {
                String sql = part.strip();
                if (sql.isEmpty()) {
                    continue;
                }
                statement.execute(sql);
            }
        }
    }

    private SchemaMigrator() {
    }
}
