package ru.longirun.gestalt.spike.ingest;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Источник: honcho_memory (только чтение, read-only пользователь — ADR 24 §6).
 * Контрольный срез: session_name + день (параметры — драфт 23).
 */
public final class HonchoPgSource implements MessageSource {

    private final String url;
    private final Properties creds;
    private final String sessionName;
    private final String day;

    public HonchoPgSource(String url, String user, String password, String sessionName, String day) {
        this.url = url;
        this.creds = new Properties();
        creds.setProperty("user", user);
        creds.setProperty("password", password);
        this.sessionName = sessionName;
        this.day = day;
    }

    @Override
    public List<RawMessage> read() throws Exception {
        String sql = """
                SELECT id, peer_name, content, token_count, created_at
                FROM messages
                WHERE session_name = ?
                  AND date_trunc('day', created_at) = ?::date
                ORDER BY id ASC
                """;
        try (Connection c = DriverManager.getConnection(url, creds);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, sessionName);
            ps.setString(2, day);
            try (ResultSet rs = ps.executeQuery()) {
                List<RawMessage> messages = new ArrayList<>();
                while (rs.next()) {
                    messages.add(new RawMessage(
                            rs.getLong("id"),
                            sessionName,
                            rs.getString("peer_name"),
                            rs.getString("content"),
                            rs.getInt("token_count"),
                            rs.getObject("created_at", OffsetDateTime.class)));
                }
                return messages;
            }
        }
    }
}
