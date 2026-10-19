package com.onlineexam.dao;

import com.onlineexam.model.Student;
import com.onlineexam.util.DbUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class StudentDAO {
    public AuthRecord findByEmail(Connection c, String email) throws SQLException {
        String sql = "SELECT student_id, name, password_hash FROM students WHERE email = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new AuthRecord(rs.getInt("student_id"), rs.getString("name"), rs.getString("password_hash")) : null;
            }
        }
    }

    /** Inserts a student and returns the generated id. A duplicate email raises SQLException (error 1062). */
    public int insert(Connection c, String name, String email, String passwordHash) throws SQLException {
        String sql = "INSERT INTO students (name, email, password_hash) VALUES (?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, email);
            ps.setString(3, passwordHash);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("No generated key returned for student");
                return keys.getInt(1);
            }
        }
    }

    public Student findById(Connection c, int studentId) throws SQLException {
        String sql = "SELECT student_id, name, email, created_at FROM students WHERE student_id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs, false) : null;
            }
        }
    }

    /** SQL-side search by name or email (LIKE). A blank keyword lists the newest 500 students. */
    public List<Student> search(Connection c, String keyword) throws SQLException {
        String sql = "SELECT s.student_id, s.name, s.email, s.created_at, "
                + "(SELECT COUNT(*) FROM exam_attempts a WHERE a.student_id = s.student_id) AS attempt_count "
                + "FROM students s WHERE s.name LIKE ? OR s.email LIKE ? ORDER BY s.student_id LIMIT 500";
        String like = "%" + escapeLike(keyword == null ? "" : keyword.trim()) + "%";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, like);
            ps.setString(2, like);
            List<Student> out = new ArrayList<Student>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs, true));
            }
            return out;
        }
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Student map(ResultSet rs, boolean withCount) throws SQLException {
        Student s = new Student(rs.getInt("student_id"), rs.getString("name"), rs.getString("email"));
        s.setCreatedAt(DbUtil.toLocal(rs.getTimestamp("created_at")));
        if (withCount) s.setAttemptCount(rs.getInt("attempt_count"));
        return s;
    }
}
