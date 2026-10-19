package com.onlineexam.dao;

import com.onlineexam.model.AttemptQuestion;
import com.onlineexam.model.AttemptStatus;
import com.onlineexam.model.AttemptSummary;
import com.onlineexam.model.ExamAttempt;
import com.onlineexam.model.ResultStatus;
import com.onlineexam.util.DbUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Attempt persistence. All time comparisons use the MySQL server clock (NOW()), never the client clock.
 */
public class AttemptDAO {
    private static final String SELECT = "SELECT a.attempt_id, a.student_id, a.exam_id, a.attempt_no, a.started_at, "
            + "a.deadline_at, a.submitted_at, a.status, TIMESTAMPDIFF(SECOND, NOW(), a.deadline_at) AS remaining_seconds, "
            + "(a.deadline_at <= NOW()) AS expired, e.title AS exam_title "
            + "FROM exam_attempts a JOIN exams e ON e.exam_id = a.exam_id ";

    /** Creates an IN_PROGRESS attempt whose deadline is (database now + exam duration). Returns the new id. */
    public long insert(Connection c, int studentId, int examId, int attemptNo) throws SQLException {
        String sql = "INSERT INTO exam_attempts (student_id, exam_id, attempt_no, started_at, deadline_at, status) "
                + "SELECT ?, e.exam_id, ?, NOW(), DATE_ADD(NOW(), INTERVAL e.duration_minutes MINUTE), 'IN_PROGRESS' "
                + "FROM exams e WHERE e.exam_id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, studentId);
            ps.setInt(2, attemptNo);
            ps.setInt(3, examId);
            if (ps.executeUpdate() != 1) throw new SQLException("Attempt row was not created");
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("No generated key returned for attempt");
                return keys.getLong(1);
            }
        }
    }

    public void insertQuestions(Connection c, long attemptId, List<AttemptQuestion> assigned) throws SQLException {
        String sql = "INSERT INTO attempt_questions (attempt_id, question_id, display_order, option_order) VALUES (?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int order = 1;
            for (AttemptQuestion q : assigned) {
                ps.setLong(1, attemptId);
                ps.setInt(2, q.getQuestionId());
                ps.setInt(3, order++);
                ps.setString(4, q.getOrderString());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public ExamAttempt findById(Connection c, long attemptId, boolean forUpdate) throws SQLException {
        String sql = SELECT + "WHERE a.attempt_id = ?" + (forUpdate ? " FOR UPDATE OF a" : "");
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, attemptId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    /** Locks and returns the student's open attempt for an exam, or null. */
    public ExamAttempt findInProgress(Connection c, int studentId, int examId) throws SQLException {
        String sql = SELECT + "WHERE a.student_id = ? AND a.exam_id = ? AND a.status = 'IN_PROGRESS' FOR UPDATE OF a";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, studentId);
            ps.setInt(2, examId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    public int countByStudentAndExam(Connection c, int studentId, int examId) throws SQLException {
        return count(c, "SELECT COUNT(*) FROM exam_attempts WHERE student_id = ? AND exam_id = ?", studentId, examId);
    }

    /** Attempts that currently occupy a seat: in progress and not past their deadline. */
    public int countActive(Connection c, int examId) throws SQLException {
        return count(c, "SELECT COUNT(*) FROM exam_attempts WHERE exam_id = ? AND status = 'IN_PROGRESS' AND deadline_at > NOW()", examId);
    }

    private static int count(Connection c, String sql, int... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setInt(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Takes a shared lock on the attempt row if (and only if) it belongs to the student, is IN_PROGRESS and its
     * deadline has not passed according to the database clock. Used before saving an answer.
     */
    public boolean lockActiveForStudent(Connection c, long attemptId, int studentId) throws SQLException {
        String sql = "SELECT 1 FROM exam_attempts WHERE attempt_id = ? AND student_id = ? AND status = 'IN_PROGRESS' "
                + "AND deadline_at > NOW() FOR SHARE";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, attemptId);
            ps.setInt(2, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Moves an IN_PROGRESS attempt to its final status. Returns the number of rows changed (1 on success). */
    public int markFinal(Connection c, long attemptId, AttemptStatus status) throws SQLException {
        String sql = "UPDATE exam_attempts SET status = ?, submitted_at = LEAST(NOW(), deadline_at) "
                + "WHERE attempt_id = ? AND status = 'IN_PROGRESS'";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setLong(2, attemptId);
            return ps.executeUpdate();
        }
    }

    /** Ids of IN_PROGRESS attempts whose deadline has passed; studentId == null means all students. */
    public List<Long> findExpiredIds(Connection c, Integer studentId) throws SQLException {
        String sql = "SELECT attempt_id FROM exam_attempts WHERE status = 'IN_PROGRESS' AND deadline_at <= NOW()"
                + (studentId == null ? "" : " AND student_id = ?");
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            if (studentId != null) ps.setInt(1, studentId);
            List<Long> ids = new ArrayList<Long>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getLong(1));
            }
            return ids;
        }
    }

    /** Admin listing; examId == null lists every exam. Newest first. */
    public List<AttemptSummary> listSummaries(Connection c, Integer examId) throws SQLException {
        String sql = "SELECT a.attempt_id, a.student_id, s.name, s.email, e.title, a.attempt_no, a.started_at, a.submitted_at, "
                + "a.status, r.score, r.max_score, r.percentage, r.status AS result_status "
                + "FROM exam_attempts a JOIN students s ON s.student_id = a.student_id "
                + "JOIN exams e ON e.exam_id = a.exam_id LEFT JOIN results r ON r.attempt_id = a.attempt_id "
                + (examId == null ? "" : "WHERE a.exam_id = ? ") + "ORDER BY a.started_at DESC, a.attempt_id DESC LIMIT 5000";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            if (examId != null) ps.setInt(1, examId);
            List<AttemptSummary> out = new ArrayList<AttemptSummary>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String rstatus = rs.getString("result_status");
                    out.add(new AttemptSummary(rs.getLong("attempt_id"), rs.getInt("student_id"), rs.getString("name"),
                            rs.getString("email"), rs.getString("title"), rs.getInt("attempt_no"),
                            DbUtil.toLocal(rs.getTimestamp("started_at")), DbUtil.toLocal(rs.getTimestamp("submitted_at")),
                            AttemptStatus.valueOf(rs.getString("status")), rs.getBigDecimal("score"),
                            rs.getBigDecimal("max_score"), rs.getBigDecimal("percentage"),
                            rstatus == null ? null : ResultStatus.valueOf(rstatus)));
                }
            }
            return out;
        }
    }

    private static ExamAttempt map(ResultSet rs) throws SQLException {
        ExamAttempt a = new ExamAttempt();
        a.setAttemptId(rs.getLong("attempt_id"));
        a.setStudentId(rs.getInt("student_id"));
        a.setExamId(rs.getInt("exam_id"));
        a.setAttemptNo(rs.getInt("attempt_no"));
        a.setStartedAt(DbUtil.toLocal(rs.getTimestamp("started_at")));
        a.setDeadlineAt(DbUtil.toLocal(rs.getTimestamp("deadline_at")));
        a.setSubmittedAt(DbUtil.toLocal(rs.getTimestamp("submitted_at")));
        a.setStatus(AttemptStatus.valueOf(rs.getString("status")));
        a.setRemainingSeconds(Math.max(0, rs.getLong("remaining_seconds")));
        a.setExpired(rs.getBoolean("expired"));
        a.setExamTitle(rs.getString("exam_title"));
        return a;
    }
}
