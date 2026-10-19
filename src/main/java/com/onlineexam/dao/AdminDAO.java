package com.onlineexam.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class AdminDAO {
    public AuthRecord findByUsername(Connection c, String username) throws SQLException {
        String sql = "SELECT admin_id, username, password_hash FROM admins WHERE username = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new AuthRecord(rs.getInt("admin_id"), rs.getString("username"), rs.getString("password_hash")) : null;
            }
        }
    }

    /** Inserts an administrator and returns the generated id. A duplicate username raises SQLException (error 1062). */
    public int insert(Connection c, String username, String passwordHash) throws SQLException {
        String sql = "INSERT INTO admins (username, password_hash) VALUES (?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, passwordHash);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("No generated key returned for administrator");
                return keys.getInt(1);
            }
        }
    }
}
