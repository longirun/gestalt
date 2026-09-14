package ru.longirun.gestalt.eval.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Словарь категорий развилок forkType (спека 33 §6): PG — источник истины, seed —
 * миграция 006 (категории project); пополняется разметчиком индуктивно до планки
 * 15 стабильных живых точек (заморозка — enum в коде, перенос в спеку 28, E6).
 * Глобальный на все материалы: таксономия развилок материал-независимая.
 */
public final class ForkTypeStore {

    public record ForkType(String key, String description, OffsetDateTime createdAt) {
    }

    private final Connection connection;

    public ForkTypeStore(Connection connection) {
        this.connection = connection;
    }

    public List<ForkType> list() throws SQLException {
        String sql = "SELECT key, description, created_at FROM fork_types ORDER BY key";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<ForkType> result = new ArrayList<>();
            while (rs.next()) {
                result.add(new ForkType(
                        rs.getString("key"), rs.getString("description"),
                        rs.getObject("created_at", OffsetDateTime.class)));
            }
            return result;
        }
    }

    public boolean exists(String key) throws SQLException {
        String sql = "SELECT 1 FROM fork_types WHERE key = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Добавление категории разметчиком (viewer): идемпотентно, описание не перезаписывает. */
    public void ensure(String key, String description) throws SQLException {
        String sql = "INSERT INTO fork_types (key, description) VALUES (?, ?) ON CONFLICT (key) DO NOTHING";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, description);
            ps.executeUpdate();
        }
    }
}
