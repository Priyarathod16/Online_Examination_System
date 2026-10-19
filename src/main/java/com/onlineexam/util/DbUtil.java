package com.onlineexam.util;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;

/** Small JDBC helpers. */
public final class DbUtil {
    private DbUtil() { }

    public static boolean isDuplicateKey(SQLException e) { return e.getErrorCode() == 1062; }

    public static boolean isForeignKeyViolation(SQLException e) { return e.getErrorCode() == 1451 || e.getErrorCode() == 1452; }

    public static void setNullableTimestamp(PreparedStatement ps, int index, LocalDateTime value) throws SQLException {
        if (value == null) ps.setNull(index, Types.TIMESTAMP); else ps.setTimestamp(index, Timestamp.valueOf(value));
    }

    public static void setNullableInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) ps.setNull(index, Types.INTEGER); else ps.setInt(index, value);
    }

    public static LocalDateTime toLocal(Timestamp ts) { return ts == null ? null : ts.toLocalDateTime(); }
}
