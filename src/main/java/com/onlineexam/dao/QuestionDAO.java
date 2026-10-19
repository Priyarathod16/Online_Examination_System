package com.onlineexam.dao;

import com.onlineexam.model.AttemptQuestion;
import com.onlineexam.model.Question;
import com.onlineexam.model.ReviewItem;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Question persistence. Methods used on the student path (findPool, findAssigned) never select correct_option.
 * Methods returning the answer key (findByExam, findAssignedWithAnswers, findReview) are only called by
 * services after a role/ownership/finalisation check.
 */
public class QuestionDAO {
    private static final String OPTS = "q.option_a, q.option_b, q.option_c, q.option_d";

    public int insert(Connection c, Question q) throws SQLException {
        String sql = "INSERT INTO questions (exam_id, question_text, option_a, option_b, option_c, option_d, correct_option, marks) "
                + "VALUES (?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, q.getExamId());
            bind(ps, q, 2);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("No generated key returned for question");
                return keys.getInt(1);
            }
        }
    }

    public int update(Connection c, Question q) throws SQLException {
        String sql = "UPDATE questions SET question_text=?, option_a=?, option_b=?, option_c=?, option_d=?, "
                + "correct_option=?, marks=? WHERE question_id=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int next = bind(ps, q, 1);
            ps.setInt(next, q.getQuestionId());
            return ps.executeUpdate();
        }
    }

    private static int bind(PreparedStatement ps, Question q, int start) throws SQLException {
        int i = start;
        ps.setString(i++, q.getText());
        for (int k = 0; k < Question.OPTION_COUNT; k++) ps.setString(i++, q.getOptions()[k]);
        ps.setString(i++, String.valueOf(q.getCorrectOption()));
        ps.setBigDecimal(i++, q.getMarks());
        return i;
    }

    /** Fails with a foreign-key error (1451) if the question was already used in an attempt. */
    public int delete(Connection c, int questionId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM questions WHERE question_id = ?")) {
            ps.setInt(1, questionId);
            return ps.executeUpdate();
        }
    }

    public int countByExam(Connection c, int examId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM questions WHERE exam_id = ?")) {
            ps.setInt(1, examId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public int findExamIdOfQuestion(Connection c, int questionId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT exam_id FROM questions WHERE question_id = ?")) {
            ps.setInt(1, questionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    /** ADMIN ONLY: full questions including answer key, ordered by id. */
    public List<Question> findByExam(Connection c, int examId) throws SQLException {
        String sql = "SELECT q.question_id, q.exam_id, q.question_text, " + OPTS + ", q.correct_option, q.marks "
                + "FROM questions q WHERE q.exam_id = ? ORDER BY q.question_id";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, examId);
            List<Question> out = new ArrayList<Question>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Question q = new Question();
                    q.setQuestionId(rs.getInt("question_id"));
                    q.setExamId(rs.getInt("exam_id"));
                    q.setText(rs.getString("question_text"));
                    q.setOptions(new String[]{rs.getString("option_a"), rs.getString("option_b"),
                            rs.getString("option_c"), rs.getString("option_d")});
                    q.setCorrectOption(rs.getString("correct_option").charAt(0));
                    q.setMarks(rs.getBigDecimal("marks"));
                    out.add(q);
                }
            }
            return out;
        }
    }

    /** STUDENT-SAFE: the pool of candidate questions for a new attempt. No answer key is selected. */
    public List<AttemptQuestion> findPool(Connection c, int examId) throws SQLException {
        String sql = "SELECT q.question_id, q.question_text, " + OPTS + ", q.marks FROM questions q "
                + "WHERE q.exam_id = ? ORDER BY q.question_id";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, examId);
            List<AttemptQuestion> out = new ArrayList<AttemptQuestion>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(AttemptQuestion.fromOrderString(rs.getInt("question_id"), rs.getString("question_text"),
                            rs.getBigDecimal("marks"), options(rs), "ABCD"));
                }
            }
            return out;
        }
    }

    /** STUDENT-SAFE: the questions assigned to an attempt, in stored display order and stored option order. */
    public List<AttemptQuestion> findAssigned(Connection c, long attemptId) throws SQLException {
        String sql = "SELECT q.question_id, q.question_text, " + OPTS + ", q.marks, aq.option_order "
                + "FROM attempt_questions aq JOIN questions q ON q.question_id = aq.question_id "
                + "WHERE aq.attempt_id = ? ORDER BY aq.display_order";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, attemptId);
            List<AttemptQuestion> out = new ArrayList<AttemptQuestion>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(AttemptQuestion.fromOrderString(rs.getInt("question_id"), rs.getString("question_text"),
                            rs.getBigDecimal("marks"), options(rs), rs.getString("option_order")));
                }
            }
            return out;
        }
    }

    /** TRUSTED (evaluation only): id, correct option and marks of the questions assigned to an attempt. */
    public List<Question> findAssignedWithAnswers(Connection c, long attemptId) throws SQLException {
        String sql = "SELECT q.question_id, q.correct_option, q.marks FROM attempt_questions aq "
                + "JOIN questions q ON q.question_id = aq.question_id WHERE aq.attempt_id = ? ORDER BY aq.display_order";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, attemptId);
            List<Question> out = new ArrayList<Question>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Question(rs.getInt("question_id"), rs.getString("correct_option").charAt(0), rs.getBigDecimal("marks")));
                }
            }
            return out;
        }
    }

    /** TRUSTED (review only, after finalisation): questions with the student's answers and the answer key. */
    public List<ReviewItem> findReview(Connection c, long attemptId) throws SQLException {
        String sql = "SELECT aq.display_order, aq.option_order, q.question_text, " + OPTS + ", q.correct_option, q.marks, "
                + "r.selected_option FROM attempt_questions aq JOIN questions q ON q.question_id = aq.question_id "
                + "LEFT JOIN responses r ON r.attempt_id = aq.attempt_id AND r.question_id = aq.question_id "
                + "WHERE aq.attempt_id = ? ORDER BY aq.display_order";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, attemptId);
            List<ReviewItem> out = new ArrayList<ReviewItem>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    AttemptQuestion shown = AttemptQuestion.fromOrderString(0, rs.getString("question_text"),
                            rs.getBigDecimal("marks"), options(rs), rs.getString("option_order"));
                    int n = shown.getOptionCount();
                    String[] texts = new String[n];
                    char[] letters = new char[n];
                    for (int i = 0; i < n; i++) {
                        texts[i] = shown.getOptionText(i);
                        letters[i] = shown.getOptionLetter(i);
                    }
                    String sel = rs.getString("selected_option");
                    out.add(new ReviewItem(rs.getInt("display_order"), shown.getText(), texts, letters,
                            sel == null ? null : Character.valueOf(sel.charAt(0)),
                            rs.getString("correct_option").charAt(0), rs.getBigDecimal("marks")));
                }
            }
            return out;
        }
    }

    private static String[] options(ResultSet rs) throws SQLException {
        return new String[]{rs.getString("option_a"), rs.getString("option_b"), rs.getString("option_c"), rs.getString("option_d")};
    }
}
