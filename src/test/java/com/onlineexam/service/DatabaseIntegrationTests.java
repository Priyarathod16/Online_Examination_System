package com.onlineexam.service;

import com.onlineexam.dao.DBConnection;
import com.onlineexam.exception.AccessDeniedException;
import com.onlineexam.exception.ExamClosedException;
import com.onlineexam.exception.ServiceException;
import com.onlineexam.exception.ValidationException;
import com.onlineexam.model.AttemptQuestion;
import com.onlineexam.model.AttemptStatus;
import com.onlineexam.model.Exam;
import com.onlineexam.model.ExamAttempt;
import com.onlineexam.model.LeaderboardEntry;
import com.onlineexam.model.Question;
import com.onlineexam.model.Result;
import com.onlineexam.model.ResultStatus;
import com.onlineexam.model.Role;
import com.onlineexam.util.AppConfig;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.onlineexam.service.Harness.assertEquals;
import static com.onlineexam.service.Harness.assertTrue;
import static com.onlineexam.service.Harness.expectThrows;
import static com.onlineexam.service.Harness.section;
import static com.onlineexam.service.Harness.test;

/**
 * End-to-end tests against a REAL MySQL database prepared with database/online_exam.sql and configured in
 * config/db.properties. The tests create their own temporary exam and students (names start with "ITEST") and delete
 * them again at the end. They never touch the sample exams. Run: scripts/integration-test.sh (or .bat).
 */
public final class DatabaseIntegrationTests {
    private static final String PASSWORD = "Passw0rd!x";

    private static final AuthService AUTH = new AuthService();
    private static final AdminService ADMIN = new AdminService();
    private static final ExamService EXAMS = new ExamService();

    private static UserSession admin;
    private static UserSession alice;
    private static UserSession bob;
    private static UserSession carol;
    private static int examId;
    private static final Map<Integer, Character> KEY = new HashMap<Integer, Character>();   // question id -> correct letter
    private static final List<String> EMAILS = new ArrayList<String>();

    private DatabaseIntegrationTests() { }

    public static void main(String[] args) throws Exception {
        String problem = DBConnection.checkAvailability();
        if (problem != null) {
            System.out.println("Cannot run integration tests: " + problem);
            System.exit(2);
        }
        long stamp = System.currentTimeMillis();
        try {
            setUp(stamp);
            authentication();
            examSetup();
            attemptFlow();
            evaluation();
            duplicateSubmission();
            timerExpiry();
            accessControl();
            databaseFailure();
        } finally {
            cleanUp();
        }
        System.exit(Harness.summary());
    }

    // ---------------------------------------------------------------- fixtures

    private static void setUp(long stamp) throws Exception {
        section("Setup (sample admin must exist: admin / Admin@123)");
        test("admin can log in", () -> admin = AUTH.login(Role.ADMIN, "admin", "Admin@123"));
        for (String n : new String[]{"alice", "bob", "carol"}) {
            final String email = "itest_" + n + "_" + stamp + "@example.test";
            EMAILS.add(email);
            test("register " + n, () -> AUTH.registerStudent("ITEST " + n, email, PASSWORD, PASSWORD));
        }
        alice = AUTH.login(Role.STUDENT, EMAILS.get(0), PASSWORD);
        bob = AUTH.login(Role.STUDENT, EMAILS.get(1), PASSWORD);
        carol = AUTH.login(Role.STUDENT, EMAILS.get(2), PASSWORD);
    }

    private static void authentication() throws Exception {
        section("Login and registration");
        test("duplicate email is rejected", () -> expectThrows(ValidationException.class,
                () -> AUTH.registerStudent("ITEST dup", EMAILS.get(0), PASSWORD, PASSWORD)));
        test("administrator can be registered with the key, then log in; duplicates and wrong keys are rejected", () -> {
            final String user = "itest_admin_" + System.currentTimeMillis();
            final String key = com.onlineexam.util.AppConfig.adminRegistrationKey();
            expectThrows(ServiceException.class, () -> AUTH.registerAdmin(user, PASSWORD, PASSWORD, key + "x"));
            AUTH.registerAdmin(user, PASSWORD, PASSWORD, key);
            UserSession created = AUTH.login(Role.ADMIN, user, PASSWORD);
            assertEquals(Role.ADMIN, created.getRole(), "role");
            expectThrows(ValidationException.class, () -> AUTH.registerAdmin(user.toUpperCase(), PASSWORD, PASSWORD, key));
        });
        test("invalid password gives a generic message (no hint which part was wrong)", () -> {
            ServiceException e = expectThrows(ServiceException.class, () -> AUTH.login(Role.STUDENT, EMAILS.get(0), "wrong-password1"));
            assertTrue(e.getMessage().startsWith("Invalid credentials"), e.getMessage());
            ServiceException unknown = expectThrows(ServiceException.class, () -> AUTH.login(Role.STUDENT, "nobody@example.test", "whatever123"));
            assertEquals(e.getMessage(), unknown.getMessage(), "same message for unknown user");
        });
        test("stored password is a hash, not the plain text", () -> {
            String stored = scalarString("SELECT password_hash FROM students WHERE email = ?", EMAILS.get(0));
            assertTrue(stored.startsWith("pbkdf2_sha256$") && !stored.contains(PASSWORD), "hash format");
        });
        test("a student account cannot log in through the admin form", () -> expectThrows(ServiceException.class,
                () -> AUTH.login(Role.ADMIN, EMAILS.get(0), PASSWORD)));
        test("repeated failures lock the account temporarily", () -> {
            for (int i = 0; i < 5; i++) {
                try {
                    AUTH.login(Role.STUDENT, "locktest@example.test", "badpassword" + i);
                } catch (ServiceException expected) { /* generic failure */ }
            }
            ServiceException e = expectThrows(ServiceException.class, () -> AUTH.login(Role.STUDENT, "locktest@example.test", "x1234567"));
            assertTrue(e.getMessage().startsWith("Too many failed attempts"), e.getMessage());
        });
    }

    private static void examSetup() throws Exception {
        section("Exam creation (admin)");
        test("admin creates an exam with 12 questions; activation needs enough questions", () -> {
            Exam e = new Exam();
            e.setTitle("ITEST exam " + System.currentTimeMillis());
            e.setDurationMinutes(30);
            e.setTotalQuestions(15);              // deliberately more than the 12 we add
            e.setPassingPercentage(new BigDecimal("50"));
            e.setMaxAttempts(3);
            e.setAllowReview(true);
            examId = ADMIN.saveExam(admin, e).getExamId();
            for (int i = 1; i <= 12; i++) {
                Question q = new Question();
                q.setExamId(examId);
                q.setText("ITEST question " + i);
                q.setOptions(new String[]{"alpha " + i, "beta " + i, "gamma " + i, "delta " + i});
                q.setCorrectOption((char) ('A' + (i % 4)));
                q.setMarks(BigDecimal.ONE);
                ADMIN.saveQuestion(admin, q);
            }
            for (Question q : ADMIN.listQuestions(admin, examId)) KEY.put(q.getQuestionId(), q.getCorrectOption());
            assertEquals(12, KEY.size(), "questions stored");
            ValidationException ve = expectThrows(ValidationException.class, () -> ADMIN.setExamActive(admin, examId, true));
            assertTrue(ve.getMessage().contains("15"), ve.getMessage());
        });
        test("starting an inactive/misconfigured exam fails with a clear message", () ->
                expectThrows(ServiceException.class, () -> EXAMS.startOrResume(alice, examId)));
        test("after reducing questions-per-attempt to 10 the exam can be activated", () -> {
            Exam e = ADMIN.listExams(admin).stream().filter(x -> x.getExamId() == examId).findFirst().get();
            e.setTotalQuestions(10);
            ADMIN.saveExam(admin, e);
            ADMIN.setExamActive(admin, examId, true);
        });
    }

    // ------------------------------------------------------------ attempt flow

    private static ExamAttempt aliceAttempt;

    private static void attemptFlow() throws Exception {
        section("Attempt, randomisation and resume");
        test("student starts an attempt: 10 unique questions, deadline stored, no answer key in the student model", () -> {
            aliceAttempt = EXAMS.startOrResume(alice, examId);
            assertEquals(10, aliceAttempt.getQuestions().size(), "question count");
            Set<Integer> ids = new HashSet<Integer>();
            for (AttemptQuestion q : aliceAttempt.getQuestions()) ids.add(q.getQuestionId());
            assertEquals(10, ids.size(), "unique ids");
            assertTrue(aliceAttempt.getRemainingSeconds() > 29 * 60, "about 30 minutes remain");
            assertEquals(AttemptStatus.IN_PROGRESS, aliceAttempt.getStatus(), "status");
            int stored = scalarInt("SELECT COUNT(*) FROM attempt_questions WHERE attempt_id = ?", aliceAttempt.getAttemptId());
            assertEquals(10, stored, "assigned questions persisted");
            for (java.lang.reflect.Method m : AttemptQuestion.class.getMethods()) {
                assertTrue(!m.getName().toLowerCase().contains("correct"), "AttemptQuestion must not expose the key: " + m.getName());
            }
        });
        test("saved answers are restored when the attempt is resumed, in the same order", () -> {
            AttemptQuestion first = aliceAttempt.getQuestions().get(0);
            AttemptQuestion second = aliceAttempt.getQuestions().get(1);
            EXAMS.saveAnswer(alice, aliceAttempt.getAttemptId(), first.getQuestionId(), first.getOptionLetter(2));
            EXAMS.saveAnswer(alice, aliceAttempt.getAttemptId(), second.getQuestionId(), second.getOptionLetter(0));
            EXAMS.saveAnswer(alice, aliceAttempt.getAttemptId(), second.getQuestionId(), second.getOptionLetter(3));  // changed her mind
            EXAMS.setMarkedForReview(alice, aliceAttempt.getAttemptId(), second.getQuestionId(), true);
            ExamAttempt resumed = EXAMS.startOrResume(alice, examId);
            assertEquals(aliceAttempt.getAttemptId(), resumed.getAttemptId(), "same attempt, no second one created");
            for (int i = 0; i < 10; i++) {
                assertEquals(aliceAttempt.getQuestions().get(i).getQuestionId(), resumed.getQuestions().get(i).getQuestionId(), "order " + i);
                assertEquals(aliceAttempt.getQuestions().get(i).getOrderString(), resumed.getQuestions().get(i).getOrderString(), "option order " + i);
            }
            assertEquals(Character.valueOf(first.getOptionLetter(2)), resumed.getAnswers().get(first.getQuestionId()), "answer 1 restored");
            assertEquals(Character.valueOf(second.getOptionLetter(3)), resumed.getAnswers().get(second.getQuestionId()), "latest answer wins");
            assertTrue(resumed.getMarked().contains(second.getQuestionId()), "review mark restored");
            assertTrue(resumed.getRemainingSeconds() <= aliceAttempt.getRemainingSeconds(), "deadline is not reset");
        });
        test("a question outside the attempt cannot be answered", () -> {
            int outside = -1;
            Set<Integer> mine = new HashSet<Integer>();
            for (AttemptQuestion q : aliceAttempt.getQuestions()) mine.add(q.getQuestionId());
            for (Integer id : KEY.keySet()) if (!mine.contains(id)) outside = id;
            final int target = outside;
            expectThrows(ValidationException.class, () -> EXAMS.saveAnswer(alice, aliceAttempt.getAttemptId(), target, 'A'));
        });
        test("another student cannot save into or submit Alice's attempt", () -> {
            AttemptQuestion q = aliceAttempt.getQuestions().get(0);
            expectThrows(ExamClosedException.class, () -> EXAMS.saveAnswer(bob, aliceAttempt.getAttemptId(), q.getQuestionId(), 'A'));
            expectThrows(AccessDeniedException.class, () -> EXAMS.submit(bob, aliceAttempt.getAttemptId(), null));
        });
        test("a second student gets a different random order", () -> {
            ExamAttempt b = EXAMS.startOrResume(bob, examId);
            List<Integer> a = ids(aliceAttempt);
            assertTrue(!a.equals(ids(b)), "orders should differ (probability of equality is negligible)");
        });
    }

    private static List<Integer> ids(ExamAttempt a) {
        List<Integer> out = new ArrayList<Integer>();
        for (AttemptQuestion q : a.getQuestions()) out.add(q.getQuestionId());
        return out;
    }

    // -------------------------------------------------------------- evaluation

    private static void evaluation() throws Exception {
        section("Evaluation and result storage");
        test("score, maximum, percentage and pass/fail are calculated and stored", () -> {
            ExamAttempt b = EXAMS.startOrResume(bob, examId);
            int correct = 0;
            int index = 0;
            for (AttemptQuestion q : b.getQuestions()) {
                char key = KEY.get(q.getQuestionId());
                if (index < 6) {                       // 6 correct
                    EXAMS.saveAnswer(bob, b.getAttemptId(), q.getQuestionId(), key);
                    correct++;
                } else if (index < 8) {                // 2 wrong
                    EXAMS.saveAnswer(bob, b.getAttemptId(), q.getQuestionId(), key == 'A' ? 'B' : 'A');
                }                                      // 2 unanswered
                index++;
            }
            Result r = EXAMS.submit(bob, b.getAttemptId(), null);
            assertEquals(new BigDecimal("6.00"), r.getScore(), "score (no negative marking)");
            assertEquals(new BigDecimal("10.00"), r.getMaxScore(), "max");
            assertEquals(new BigDecimal("60.00"), r.getPercentage(), "percentage");
            assertEquals(ResultStatus.PASS, r.getStatus(), "pass at 50%");
            assertEquals(AttemptStatus.SUBMITTED, r.getAttemptStatus(), "attempt status");
            assertEquals(1, scalarInt("SELECT COUNT(*) FROM results WHERE attempt_id = ?", b.getAttemptId()), "one stored result");
            assertEquals(6, correct, "sanity");
        });
        test("answer review is available only because the exam allows it, and only to the owner", () -> {
            long bobAttempt = scalarLong("SELECT attempt_id FROM exam_attempts WHERE exam_id = ? AND student_id = (SELECT student_id FROM students WHERE email = ?)", examId, EMAILS.get(1));
            assertEquals(10, EXAMS.getReview(bob, bobAttempt).size(), "review items");
            expectThrows(AccessDeniedException.class, () -> EXAMS.getReview(carol, bobAttempt));
        });
        test("review is refused while the attempt is still running (answers never exposed early)", () ->
                expectThrows(AccessDeniedException.class, () -> EXAMS.getReview(alice, aliceAttempt.getAttemptId())));
        test("leaderboard ranks the finished attempt", () -> {
            List<LeaderboardEntry> board = EXAMS.getLeaderboard(alice, examId);
            assertEquals(1, board.size(), "one finished attempt so far");
            assertEquals(1, board.get(0).getRank(), "rank");
        });
        test("history lists the student's results", () -> assertEquals(1, EXAMS.getHistory(bob).size(), "history rows"));
    }

    // ------------------------------------------------------ duplicate submission

    private static void duplicateSubmission() throws Exception {
        section("Duplicate submission");
        test("submitting twice returns the same result and stores exactly one", () -> {
            Result first = EXAMS.submit(alice, aliceAttempt.getAttemptId(), null);
            Result second = EXAMS.submit(alice, aliceAttempt.getAttemptId(), null);
            assertEquals(first.getResultId(), second.getResultId(), "same result row");
            assertEquals(1, scalarInt("SELECT COUNT(*) FROM results WHERE attempt_id = ?", aliceAttempt.getAttemptId()), "result rows");
        });
        test("8 simultaneous submits (double-click / retry storm) create exactly one result", () -> {
            final ExamAttempt c = EXAMS.startOrResume(carol, examId);
            ExecutorService pool = Executors.newFixedThreadPool(8);
            List<Future<Long>> futures = new ArrayList<Future<Long>>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(new Callable<Long>() {
                    @Override
                    public Long call() throws Exception {
                        return EXAMS.submit(carol, c.getAttemptId(), null).getResultId();
                    }
                }));
            }
            Set<Long> resultIds = new HashSet<Long>();
            for (Future<Long> f : futures) resultIds.add(f.get());
            pool.shutdown();
            assertEquals(1, resultIds.size(), "all callers see the same result");
            assertEquals(1, scalarInt("SELECT COUNT(*) FROM results WHERE attempt_id = ?", c.getAttemptId()), "result rows");
        });
        test("the database itself refuses a second result for an attempt", () -> {
            try (Connection conn = DBConnection.getConnection(); PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO results (attempt_id, score, max_score, percentage, status) VALUES (?, 0, 10, 0, 'FAIL')")) {
                ps.setLong(1, aliceAttempt.getAttemptId());
                expectThrows(SQLException.class, () -> ps.executeUpdate());
            }
        });
    }

    // ------------------------------------------------------------- timer expiry

    private static void timerExpiry() throws Exception {
        section("Timer expiry (deadline enforced from the database)");
        test("after the stored deadline passes, saves are rejected and submit records TIMED_OUT with earlier answers only", () -> {
            // fresh exam attempt for Bob: attempts allowed = 3, he used 1
            ExamAttempt b = EXAMS.startOrResume(bob, examId);
            AttemptQuestion q1 = b.getQuestions().get(0);
            AttemptQuestion q2 = b.getQuestions().get(1);
            EXAMS.saveAnswer(bob, b.getAttemptId(), q1.getQuestionId(), (char) (KEY.get(q1.getQuestionId())));   // correct, saved in time
            // simulate time passing: move the stored start and deadline into the past
            execute("UPDATE exam_attempts SET started_at = NOW() - INTERVAL 2 HOUR, deadline_at = NOW() - INTERVAL 1 HOUR WHERE attempt_id = ?", b.getAttemptId());
            expectThrows(ExamClosedException.class, () -> EXAMS.saveAnswer(bob, b.getAttemptId(), q2.getQuestionId(), (char) (KEY.get(q2.getQuestionId()))));
            Map<Integer, Character> late = new HashMap<Integer, Character>();
            late.put(q2.getQuestionId(), KEY.get(q2.getQuestionId()));
            Result r = EXAMS.submit(bob, b.getAttemptId(), late);            // late pending answers must be ignored
            assertEquals(AttemptStatus.TIMED_OUT, r.getAttemptStatus(), "timed out");
            assertEquals(new BigDecimal("1.00"), r.getScore(), "only the answer saved before the deadline counts");
            assertEquals(1, scalarInt("SELECT COUNT(*) FROM results WHERE attempt_id = ?", b.getAttemptId()), "one result");
        });
        test("an abandoned, expired attempt is finalised automatically when the student returns", () -> {
            ExamAttempt b = EXAMS.startOrResume(bob, examId);     // third and last attempt
            execute("UPDATE exam_attempts SET started_at = NOW() - INTERVAL 2 HOUR, deadline_at = NOW() - INTERVAL 1 HOUR WHERE attempt_id = ?", b.getAttemptId());
            EXAMS.listAvailableExams(bob);                        // dashboard load triggers finalisation
            assertEquals("TIMED_OUT", scalarString("SELECT status FROM exam_attempts WHERE attempt_id = ?", b.getAttemptId()), "status");
            assertEquals(1, scalarInt("SELECT COUNT(*) FROM results WHERE attempt_id = ?", b.getAttemptId()), "result stored");
            ServiceException e = expectThrows(ServiceException.class, () -> EXAMS.startOrResume(bob, examId));
            assertTrue(e.getMessage().contains("all 3"), "no attempts left: " + e.getMessage());
        });
    }

    // ----------------------------------------------------------- access control

    private static void accessControl() throws Exception {
        section("Access control");
        test("student cannot call admin functions", () -> {
            expectThrows(AccessDeniedException.class, () -> ADMIN.listExams(alice));
            expectThrows(AccessDeniedException.class, () -> ADMIN.listQuestions(alice, examId));
            expectThrows(AccessDeniedException.class, () -> ADMIN.deleteExam(alice, examId));
        });
        test("an exam with attempts cannot be deleted (history is protected)", () ->
                expectThrows(ValidationException.class, () -> ADMIN.deleteExam(admin, examId)));
    }

    private static void databaseFailure() throws Exception {
        section("Database failure");
        test("database going away produces a controlled error (nothing reported as saved)", () -> {
            AppConfig.override("jdbc:mysql://127.0.0.1:1/online_exam?connectTimeout=1000", "x", "y");
            try {
                expectThrows(ServiceException.class, () -> EXAMS.saveAnswer(alice, aliceAttempt.getAttemptId(), aliceAttempt.getQuestions().get(0).getQuestionId(), 'A'));
            } finally {
                AppConfig.reset();
            }
        });
    }

    // ------------------------------------------------------------------ cleanup

    private static void cleanUp() {
        section("Cleanup");
        try (Connection c = DBConnection.getConnection()) {
            String attemptsOfExam = "SELECT attempt_id FROM exam_attempts WHERE exam_id = " + Math.max(examId, -1);
            c.createStatement().executeUpdate("DELETE FROM results WHERE attempt_id IN (" + attemptsOfExam + ")");
            c.createStatement().executeUpdate("DELETE FROM responses WHERE attempt_id IN (" + attemptsOfExam + ")");
            c.createStatement().executeUpdate("DELETE FROM attempt_questions WHERE attempt_id IN (" + attemptsOfExam + ")");
            c.createStatement().executeUpdate("DELETE FROM exam_attempts WHERE exam_id = " + Math.max(examId, -1));
            c.createStatement().executeUpdate("DELETE FROM exams WHERE exam_id = " + Math.max(examId, -1));
            c.createStatement().executeUpdate("DELETE FROM students WHERE email LIKE 'itest\\_%@example.test'");
            System.out.println("  test data removed");
        } catch (SQLException e) {
            System.out.println("  cleanup failed (remove rows named ITEST manually): " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ JDBC helpers

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
    }

    private static int scalarInt(String sql, Object... params) throws SQLException {
        return (int) scalarLong(sql, params);
    }

    private static long scalarLong(String sql, Object... params) throws SQLException {
        try (Connection c = DBConnection.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static String scalarString(String sql, Object... params) throws SQLException {
        try (Connection c = DBConnection.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static void execute(String sql, Object... params) throws SQLException {
        try (Connection c = DBConnection.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            ps.executeUpdate();
        }
    }
}
