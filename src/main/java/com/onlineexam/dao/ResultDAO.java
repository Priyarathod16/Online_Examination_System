package com.onlineexam.dao;

import com.onlineexam.model.AttemptStatus;
import com.onlineexam.model.HistoryEntry;
import com.onlineexam.model.LeaderboardEntry;
import com.onlineexam.model.Result;
import com.onlineexam.model.ResultStatus;
import com.onlineexam.util.DbUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class ResultDAO {
    /** The UNIQUE constraint on results.attempt_id makes a second insert for the same attempt fail (error 1062). */
    public void insert(Connection c, long attemptId, Result r) throws SQLException {
        String sql = "INSERT INTO results (attempt_id, score, max_score, percentage, status) VALUES (?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, attemptId);
            ps.setBigDecimal(2, r.getScore());
            ps.setBigDecimal(3, r.getMaxScore());
            ps.setBigDecimal(4, r.getPercentage());
            ps.setString(5, r.getStatus().name());
            ps.executeUpdate();
        }
    }

    public Result findByAttempt(Connection c, long attemptId) throws SQLException {
        String sql = "SELECT r.result_id, r.attempt_id, r.score, r.max_score, r.percentage, r.status, "
                + "a.status AS attempt_status, e.exam_id, e.title, e.allow_review "
                + "FROM results r JOIN exam_attempts a ON a.attempt_id = r.attempt_id "
                + "JOIN exams e ON e.exam_id = a.exam_id WHERE r.attempt_id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, attemptId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Result r = new Result(rs.getBigDecimal("score"), rs.getBigDecimal("max_score"),
                        rs.getBigDecimal("percentage"), ResultStatus.valueOf(rs.getString("status")));
                r.setResultId(rs.getLong("result_id"));
                r.setAttemptId(rs.getLong("attempt_id"));
                r.setExamId(rs.getInt("exam_id"));
                r.setExamTitle(rs.getString("title"));
                r.setAttemptStatus(AttemptStatus.valueOf(rs.getString("attempt_status")));
                r.setReviewAllowed(rs.getBoolean("allow_review"));
                return r;
            }
        }
    }

    /** Unsorted, unranked result rows of an exam; sorting and ranking is done in Java by RankingService. */
    public List<LeaderboardEntry> findRankingRows(Connection c, int examId) throws SQLException {
        String sql = "SELECT a.student_id, s.name, a.attempt_id, r.score, r.max_score, r.percentage, r.status, a.submitted_at "
                + "FROM results r JOIN exam_attempts a ON a.attempt_id = r.attempt_id "
                + "JOIN students s ON s.student_id = a.student_id WHERE a.exam_id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, examId);
            List<LeaderboardEntry> out = new ArrayList<LeaderboardEntry>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new LeaderboardEntry(0, rs.getInt("student_id"), rs.getString("name"), rs.getLong("attempt_id"),
                            rs.getBigDecimal("score"), rs.getBigDecimal("max_score"), rs.getBigDecimal("percentage"),
                            ResultStatus.valueOf(rs.getString("status")), DbUtil.toLocal(rs.getTimestamp("submitted_at"))));
                }
            }
            return out;
        }
    }

    public List<HistoryEntry> findHistory(Connection c, int studentId) throws SQLException {
        String sql = "SELECT a.attempt_id, e.exam_id, e.title, a.attempt_no, r.score, r.max_score, r.percentage, "
                + "r.status, a.status AS attempt_status, a.submitted_at, e.allow_review "
                + "FROM exam_attempts a JOIN exams e ON e.exam_id = a.exam_id JOIN results r ON r.attempt_id = a.attempt_id "
                + "WHERE a.student_id = ? ORDER BY a.submitted_at DESC, a.attempt_id DESC";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, studentId);
            List<HistoryEntry> out = new ArrayList<HistoryEntry>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new HistoryEntry(rs.getLong("attempt_id"), rs.getInt("exam_id"), rs.getString("title"),
                            rs.getInt("attempt_no"), rs.getBigDecimal("score"), rs.getBigDecimal("max_score"),
                            rs.getBigDecimal("percentage"), ResultStatus.valueOf(rs.getString("status")),
                            AttemptStatus.valueOf(rs.getString("attempt_status")),
                            DbUtil.toLocal(rs.getTimestamp("submitted_at")), rs.getBoolean("allow_review")));
                }
            }
            return out;
        }
    }
}
