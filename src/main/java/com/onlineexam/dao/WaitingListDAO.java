package com.onlineexam.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Persistent FIFO waiting list for exams with a seat limit (ordered by the auto-increment waiting_id). */
public class WaitingListDAO {
    public void enqueue(Connection c, int examId, int studentId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO waiting_list (exam_id, student_id) VALUES (?, ?)")) {
            ps.setInt(1, examId);
            ps.setInt(2, studentId);
            ps.executeUpdate();
        }
    }

    public void remove(Connection c, int examId, int studentId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM waiting_list WHERE exam_id = ? AND student_id = ?")) {
            ps.setInt(1, examId);
            ps.setInt(2, studentId);
            ps.executeUpdate();
        }
    }

    /** Student ids in first-come-first-served order. */
    public List<Integer> queue(Connection c, int examId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT student_id FROM waiting_list WHERE exam_id = ? ORDER BY waiting_id")) {
            ps.setInt(1, examId);
            List<Integer> out = new ArrayList<Integer>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getInt(1));
            }
            return out;
        }
    }
}
