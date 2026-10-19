package com.onlineexam.dao;

import com.onlineexam.model.Exam;
import com.onlineexam.util.DbUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class ExamDAO {
    private static final String COLS = "e.exam_id, e.title, e.description, e.instructions, e.duration_minutes, "
            + "e.total_questions, e.passing_percentage, e.negative_marks, e.start_time, e.end_time, e.is_active, "
            + "e.shuffle_questions, e.shuffle_options, e.allow_review, e.max_attempts, e.max_concurrent";
    private static final String QCOUNT = "(SELECT COUNT(*) FROM questions q WHERE q.exam_id = e.exam_id) AS question_count";
    private static final String OPEN_NOW = "e.is_active = 1 AND (e.start_time IS NULL OR e.start_time <= NOW()) "
            + "AND (e.end_time IS NULL OR e.end_time > NOW())";

    public int insert(Connection c, Exam e, int createdBy) throws SQLException {
        String sql = "INSERT INTO exams (title, description, instructions, duration_minutes, total_questions, "
                + "passing_percentage, negative_marks, start_time, end_time, is_active, shuffle_questions, "
                + "shuffle_options, allow_review, max_attempts, max_concurrent, created_by) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            int next = bind(ps, e);
            ps.setInt(next, createdBy);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("No generated key returned for exam");
                return keys.getInt(1);
            }
        }
    }

    public int update(Connection c, Exam e) throws SQLException {
        String sql = "UPDATE exams SET title=?, description=?, instructions=?, duration_minutes=?, total_questions=?, "
                + "passing_percentage=?, negative_marks=?, start_time=?, end_time=?, is_active=?, shuffle_questions=?, "
                + "shuffle_options=?, allow_review=?, max_attempts=?, max_concurrent=? WHERE exam_id=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int next = bind(ps, e);
            ps.setInt(next, e.getExamId());
            return ps.executeUpdate();
        }
    }

    /** Binds the 15 editable columns and returns the next free parameter index (16). */
    private static int bind(PreparedStatement ps, Exam e) throws SQLException {
        int i = 1;
        ps.setString(i++, e.getTitle());
        ps.setString(i++, e.getDescription());
        ps.setString(i++, e.getInstructions());
        ps.setInt(i++, e.getDurationMinutes());
        ps.setInt(i++, e.getTotalQuestions());
        ps.setBigDecimal(i++, e.getPassingPercentage());
        ps.setBigDecimal(i++, e.getNegativeMarks());
        DbUtil.setNullableTimestamp(ps, i++, e.getStartTime());
        DbUtil.setNullableTimestamp(ps, i++, e.getEndTime());
        ps.setBoolean(i++, e.isActive());
        ps.setBoolean(i++, e.isShuffleQuestions());
        ps.setBoolean(i++, e.isShuffleOptions());
        ps.setBoolean(i++, e.isAllowReview());
        ps.setInt(i++, e.getMaxAttempts());
        DbUtil.setNullableInt(ps, i++, e.getMaxConcurrent());
        return i;
    }

    public int setActive(Connection c, int examId, boolean active) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE exams SET is_active = ? WHERE exam_id = ?")) {
            ps.setBoolean(1, active);
            ps.setInt(2, examId);
            return ps.executeUpdate();
        }
    }

    /** Fails with a foreign-key error (1451) when attempts exist for the exam. */
    public int delete(Connection c, int examId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM exams WHERE exam_id = ?")) {
            ps.setInt(1, examId);
            return ps.executeUpdate();
        }
    }

    public Exam findById(Connection c, int examId, boolean forUpdate) throws SQLException {
        String sql = "SELECT " + COLS + ", " + QCOUNT + " FROM exams e WHERE e.exam_id = ?" + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, examId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Exam e = map(rs);
                e.setQuestionCount(rs.getInt("question_count"));
                return e;
            }
        }
    }

    /** All exams for the admin screens, ordered by id (so binary search by id is valid). */
    public List<Exam> listAll(Connection c) throws SQLException {
        return list(c, "SELECT " + COLS + ", " + QCOUNT + " FROM exams e ORDER BY e.exam_id");
    }

    /** Active exams (any time window), used for leaderboard pickers. */
    public List<Exam> listActive(Connection c) throws SQLException {
        return list(c, "SELECT " + COLS + ", " + QCOUNT + " FROM exams e WHERE e.is_active = 1 ORDER BY e.exam_id");
    }

    private List<Exam> list(Connection c, String sql) throws SQLException {
        List<Exam> out = new ArrayList<Exam>();
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Exam e = map(rs);
                e.setQuestionCount(rs.getInt("question_count"));
                out.add(e);
            }
        }
        return out;
    }

    /** Exams a student may currently start or resume, with that student's attempt information. */
    public List<Exam> listAvailable(Connection c, int studentId) throws SQLException {
        String sql = "SELECT " + COLS + ", " + QCOUNT + ", "
                + "(SELECT COUNT(*) FROM exam_attempts a WHERE a.exam_id = e.exam_id AND a.student_id = ?) AS attempts_used, "
                + "(SELECT a2.attempt_id FROM exam_attempts a2 WHERE a2.exam_id = e.exam_id AND a2.student_id = ? "
                + "AND a2.status = 'IN_PROGRESS' LIMIT 1) AS active_attempt_id "
                + "FROM exams e WHERE " + OPEN_NOW + " ORDER BY e.exam_id";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, studentId);
            ps.setInt(2, studentId);
            List<Exam> out = new ArrayList<Exam>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Exam e = map(rs);
                    e.setQuestionCount(rs.getInt("question_count"));
                    e.setAttemptsUsed(rs.getInt("attempts_used"));
                    long active = rs.getLong("active_attempt_id");
                    e.setActiveAttemptId(rs.wasNull() ? null : Long.valueOf(active));
                    out.add(e);
                }
            }
            return out;
        }
    }

    /** True when the exam is active and the current database time is inside its availability window. */
    public boolean isOpenNow(Connection c, int examId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM exams e WHERE e.exam_id = ? AND " + OPEN_NOW;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, examId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    private static Exam map(ResultSet rs) throws SQLException {
        Exam e = new Exam();
        e.setExamId(rs.getInt("exam_id"));
        e.setTitle(rs.getString("title"));
        e.setDescription(rs.getString("description"));
        e.setInstructions(rs.getString("instructions"));
        e.setDurationMinutes(rs.getInt("duration_minutes"));
        e.setTotalQuestions(rs.getInt("total_questions"));
        e.setPassingPercentage(rs.getBigDecimal("passing_percentage"));
        e.setNegativeMarks(rs.getBigDecimal("negative_marks"));
        e.setStartTime(DbUtil.toLocal(rs.getTimestamp("start_time")));
        e.setEndTime(DbUtil.toLocal(rs.getTimestamp("end_time")));
        e.setActive(rs.getBoolean("is_active"));
        e.setShuffleQuestions(rs.getBoolean("shuffle_questions"));
        e.setShuffleOptions(rs.getBoolean("shuffle_options"));
        e.setAllowReview(rs.getBoolean("allow_review"));
        e.setMaxAttempts(rs.getInt("max_attempts"));
        int mc = rs.getInt("max_concurrent");
        e.setMaxConcurrent(rs.wasNull() ? null : Integer.valueOf(mc));
        return e;
    }
}
