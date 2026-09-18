package ru.longirun.gestalt.eval.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** NULL-safe доступ к BIGINT-колонкам — общие хелперы сторов прогона (RunStore, ResultStore). */
final class Sql {

    private Sql() {
    }

    static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    static Long getNullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
