package com.onlineexam.dao;

import com.onlineexam.model.Response;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashMap;
import java.util.Map;

/** Responses are keyed by question id (never by screen position). */
public class ResponseDAO {
    /**
     * Upserts the selected option (null clears the answer). The composite foreign key to attempt_questions makes
     * MySQL reject questions that were not assigned to this attempt (error 1452).
     */
    public void saveAnswer(Connection c, long attemptId, int questionId, Character option) throws SQLException {
        String sql = "INSERT INTO responses (attempt_id, question_id, selected_option) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE selected_option = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, attemptId);
            ps.setInt(2, questionId);
            if (option == null) {
                ps.setNull(3, Types.CHAR);
                ps.setNull(4, Types.CHAR);
            } else {
                ps.setString(3, option.toString());
                ps.setString(4, option.toString());
            }
            ps.executeUpdate();
        }
    }

    public void saveMark(Connection c, long attemptId, int questionId, boolean marked) throws SQLException {
        String sql = "INSERT INTO responses (attempt_id, question_id, marked_for_review) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE marked_for_review = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, attemptId);
            ps.setInt(2, questionId);
            ps.setBoolean(3, marked);
            ps.setBoolean(4, marked);
            ps.executeUpdate();
        }
    }

    /** question id -> stored response (HashMap: O(1) average lookup). */
    public Map<Integer, Response> loadResponses(Connection c, long attemptId) throws SQLException {
        String sql = "SELECT question_id, selected_option, marked_for_review FROM responses WHERE attempt_id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, attemptId);
            Map<Integer, Response> out = new HashMap<Integer, Response>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String sel = rs.getString("selected_option");
                    out.put(rs.getInt("question_id"), new Response(rs.getInt("question_id"),
                            sel == null ? null : Character.valueOf(sel.charAt(0)), rs.getBoolean("marked_for_review")));
                }
            }
            return out;
        }
    }

    /** question id -> selected original option letter, answered questions only. */
    public Map<Integer, Character> loadSelected(Connection c, long attemptId) throws SQLException {
        Map<Integer, Character> out = new HashMap<Integer, Character>();
        for (Response r : loadResponses(c, attemptId).values()) {
            if (r.getSelectedOption() != null) out.put(r.getQuestionId(), r.getSelectedOption());
        }
        return out;
    }
}
