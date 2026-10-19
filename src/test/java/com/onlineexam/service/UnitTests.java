package com.onlineexam.service;

import com.onlineexam.exception.AccessDeniedException;
import com.onlineexam.exception.ServiceException;
import com.onlineexam.exception.ValidationException;
import com.onlineexam.model.AttemptQuestion;
import com.onlineexam.model.Exam;
import com.onlineexam.model.LeaderboardEntry;
import com.onlineexam.model.Question;
import com.onlineexam.model.Result;
import com.onlineexam.model.ResultStatus;
import com.onlineexam.model.Role;
import com.onlineexam.util.AppConfig;
import com.onlineexam.util.DateUtil;
import com.onlineexam.util.PasswordUtil;
import com.onlineexam.util.SearchUtil;
import com.onlineexam.util.ShuffleUtil;
import com.onlineexam.util.ValidationUtil;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static com.onlineexam.service.Harness.assertEquals;
import static com.onlineexam.service.Harness.assertTrue;
import static com.onlineexam.service.Harness.expectThrows;
import static com.onlineexam.service.Harness.section;
import static com.onlineexam.service.Harness.test;

/** Tests for all logic that does not need a database. Run: scripts/test.sh (or test.bat). */
public final class UnitTests {
    private UnitTests() { }

    public static void main(String[] args) {
        passwords();
        validation();
        shuffle();
        randomizer();
        evaluation();
        ranking();
        waitingList();
        searching();
        adminValidation();
        accessControl();
        databaseFailure();
        System.exit(Harness.summary());
    }

    // ---------------------------------------------------------------- helpers

    private static AttemptQuestion aq(int id) {
        return AttemptQuestion.create(id, "Question " + id, BigDecimal.ONE,
                new String[]{"opt A " + id, "opt B " + id, "opt C " + id, "opt D " + id}, new int[]{0, 1, 2, 3});
    }

    private static List<AttemptQuestion> pool(int n) {
        List<AttemptQuestion> p = new ArrayList<AttemptQuestion>();
        for (int i = 1; i <= n; i++) p.add(aq(i));
        return p;
    }

    private static Question key(int id, char correct, String marks) {
        return new Question(id, correct, new BigDecimal(marks));
    }

    private static LeaderboardEntry entry(int student, String name, String score, String pct, LocalDateTime at, long attempt) {
        return new LeaderboardEntry(0, student, name, attempt, new BigDecimal(score), new BigDecimal("10"),
                new BigDecimal(pct), ResultStatus.PASS, at);
    }

    // ------------------------------------------------------------------ tests

    private static void passwords() {
        section("Passwords (login security)");
        test("hash is salted: same password gives different hashes", () -> {
            String a = PasswordUtil.hash("Secret123");
            String b = PasswordUtil.hash("Secret123");
            assertTrue(!a.equals(b), "two hashes of the same password must differ");
            assertTrue(!a.contains("Secret123"), "hash must not contain the plain password");
        });
        test("correct password verifies, wrong password is rejected", () -> {
            String h = PasswordUtil.hash("Secret123");
            assertTrue(PasswordUtil.verify("Secret123", h), "correct password");
            assertTrue(!PasswordUtil.verify("secret123", h), "wrong case");
            assertTrue(!PasswordUtil.verify("", h), "empty");
        });
        test("malformed or plain-text stored values never verify", () -> {
            assertTrue(!PasswordUtil.verify("x", "plaintext"), "plain text");
            assertTrue(!PasswordUtil.verify("x", "pbkdf2_sha256$abc$!!$!!"), "garbage");
            assertTrue(!PasswordUtil.verify("x", null), "null");
        });
    }

    private static void validation() {
        section("Input validation");
        test("email validation", () -> {
            assertEquals("a@b.co", ValidationUtil.requireEmail("  A@B.co "), "normalised");
            expectThrows(ValidationException.class, () -> ValidationUtil.requireEmail("not-an-email"));
            expectThrows(ValidationException.class, () -> ValidationUtil.requireEmail(""));
        });
        test("password policy", () -> {
            ValidationUtil.requirePassword("abcdefg1");
            expectThrows(ValidationException.class, () -> ValidationUtil.requirePassword("short1"));
            expectThrows(ValidationException.class, () -> ValidationUtil.requirePassword("onlyletters"));
            expectThrows(ValidationException.class, () -> ValidationUtil.requirePassword("12345678"));
        });
        test("administrator username rules", () -> {
            assertEquals("new.admin_1", ValidationUtil.requireUsername("  new.admin_1 "), "trimmed");
            expectThrows(ValidationException.class, () -> ValidationUtil.requireUsername(""));
            expectThrows(ValidationException.class, () -> ValidationUtil.requireUsername("ab"));
            expectThrows(ValidationException.class, () -> ValidationUtil.requireUsername("has space"));
            expectThrows(ValidationException.class, () -> ValidationUtil.requireUsername("semi;colon"));
        });
        test("number parsing rejects bad input", () -> {
            expectThrows(ValidationException.class, () -> ValidationUtil.parseInt("abc", "Duration"));
            expectThrows(ValidationException.class, () -> ValidationUtil.parseDecimal("1.234", "Marks"));
            assertEquals(new BigDecimal("2.5"), ValidationUtil.parseDecimal("2.5", "Marks"), "decimal");
        });
        test("timer text formatting", () -> {
            assertEquals("01:05", DateUtil.formatSeconds(65), "mm:ss");
            assertEquals("1:00:00", DateUtil.formatSeconds(3600), "h:mm:ss");
            assertEquals("00:00", DateUtil.formatSeconds(-5), "never negative");
        });
    }

    private static void shuffle() {
        section("Fisher-Yates shuffle");
        test("result is a permutation (no loss, no duplicates)", () -> {
            Integer[] data = new Integer[50];
            for (int i = 0; i < data.length; i++) data[i] = i;
            ShuffleUtil.shuffle(data, new Random(7));
            Set<Integer> seen = new HashSet<Integer>(Arrays.asList(data));
            assertEquals(50, seen.size(), "all elements present exactly once");
        });
        test("same seed gives the same order; different seeds usually differ", () -> {
            List<Integer> a = new ArrayList<Integer>(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8));
            List<Integer> b = new ArrayList<Integer>(a);
            List<Integer> c = new ArrayList<Integer>(a);
            ShuffleUtil.shuffle(a, new Random(1));
            ShuffleUtil.shuffle(b, new Random(1));
            ShuffleUtil.shuffle(c, new Random(2));
            assertEquals(a, b, "deterministic for a seed");
            assertTrue(!a.equals(c), "different seeds should give different orders here");
        });
        test("uniform: all 6 permutations of 3 items appear about equally often", () -> {
            Map<String, Integer> counts = new HashMap<String, Integer>();
            Random r = new Random(12345);
            int trials = 60000;
            for (int i = 0; i < trials; i++) {
                int[] p = ShuffleUtil.identity(3);
                ShuffleUtil.shuffle(p, r);
                String k = Arrays.toString(p);
                counts.put(k, counts.containsKey(k) ? counts.get(k) + 1 : 1);
            }
            assertEquals(6, counts.size(), "number of distinct permutations");
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                assertTrue(Math.abs(e.getValue() - trials / 6.0) < trials / 6.0 * 0.06, "bias in " + e.getKey() + " = " + e.getValue());
            }
        });
    }

    private static void randomizer() {
        section("Question randomisation");
        test("selected question ids are unique and the count is exact", () -> {
            List<AttemptQuestion> chosen = new QuestionRandomizer(new Random(3)).assign(pool(30), 10, true, true);
            assertEquals(10, chosen.size(), "count");
            Set<Integer> ids = new HashSet<Integer>();
            for (AttemptQuestion q : chosen) ids.add(q.getQuestionId());
            assertEquals(10, ids.size(), "unique ids within an attempt");
        });
        test("order differs across attempts", () -> {
            QuestionRandomizer r = new QuestionRandomizer(new Random(99));
            List<Integer> first = ids(r.assign(pool(30), 10, true, true));
            List<Integer> second = ids(r.assign(pool(30), 10, true, true));
            assertTrue(!first.equals(second), "two attempts should not have identical order");
        });
        test("shuffling disabled keeps the first N questions by id in order", () -> {
            List<Integer> ids = ids(new QuestionRandomizer(new Random(5)).assign(pool(12), 5, false, false));
            assertEquals(Arrays.asList(1, 2, 3, 4, 5), ids, "fixed order");
        });
        test("option shuffle keeps every option text paired with its ORIGINAL letter", () -> {
            AttemptQuestion shuffled = new QuestionRandomizer(new Random(8)).assign(pool(1), 1, true, true).get(0);
            Set<Character> letters = new HashSet<Character>();
            for (int i = 0; i < 4; i++) {
                char letter = shuffled.getOptionLetter(i);
                letters.add(letter);
                assertEquals("opt " + letter + " 1", shuffled.getOptionText(i), "text matches original letter");
            }
            assertEquals(4, letters.size(), "all four letters present");
        });
        test("stored option order string can rebuild the same display", () -> {
            AttemptQuestion shuffled = new QuestionRandomizer(new Random(21)).assign(pool(1), 1, true, true).get(0);
            AttemptQuestion rebuilt = AttemptQuestion.fromOrderString(1, "Question 1", BigDecimal.ONE,
                    new String[]{"opt A 1", "opt B 1", "opt C 1", "opt D 1"}, shuffled.getOrderString());
            for (int i = 0; i < 4; i++) {
                assertEquals(shuffled.getOptionText(i), rebuilt.getOptionText(i), "option " + i);
                assertEquals(shuffled.getOptionLetter(i), rebuilt.getOptionLetter(i), "letter " + i);
            }
        });
        test("exam with fewer questions than required gives a clear configuration error", () -> {
            ValidationException e = expectThrows(ValidationException.class,
                    () -> new QuestionRandomizer().assign(pool(4), 10, true, true));
            assertTrue(e.getMessage().contains("needs 10") && e.getMessage().contains("only 4"), "message: " + e.getMessage());
        });
    }

    private static List<Integer> ids(List<AttemptQuestion> list) {
        List<Integer> out = new ArrayList<Integer>();
        for (AttemptQuestion q : list) out.add(q.getQuestionId());
        return out;
    }

    private static void evaluation() {
        section("Answer evaluation");
        final EvaluationService ev = new EvaluationService();
        final List<Question> keyList = Arrays.asList(key(1, 'A', "1"), key(2, 'B', "1"), key(3, 'C', "2"), key(4, 'D', "1"));
        final BigDecimal pass40 = new BigDecimal("40");
        test("correct answers award the configured marks", () -> {
            Map<Integer, Character> a = new HashMap<Integer, Character>();
            a.put(1, 'A');
            a.put(3, 'C');
            Result r = ev.evaluate(keyList, a, BigDecimal.ZERO, pass40);
            assertEquals(new BigDecimal("3.00"), r.getScore(), "score");
            assertEquals(new BigDecimal("5.00"), r.getMaxScore(), "max");
            assertEquals(new BigDecimal("60.00"), r.getPercentage(), "percentage");
            assertEquals(ResultStatus.PASS, r.getStatus(), "status");
        });
        test("wrong and unanswered questions score zero without negative marking", () -> {
            Map<Integer, Character> a = new HashMap<Integer, Character>();
            a.put(1, 'B');
            a.put(2, 'A');
            Result r = ev.evaluate(keyList, a, BigDecimal.ZERO, pass40);
            assertEquals(new BigDecimal("0.00"), r.getScore(), "score");
            assertEquals(ResultStatus.FAIL, r.getStatus(), "status");
        });
        test("negative marking deducts only for wrong answers, never below zero", () -> {
            Map<Integer, Character> a = new HashMap<Integer, Character>();
            a.put(1, 'A');     // +1
            a.put(2, 'C');     // wrong  -0.5
            // question 3 and 4 unanswered -> no penalty
            Result r = ev.evaluate(keyList, a, new BigDecimal("0.5"), pass40);
            assertEquals(new BigDecimal("0.50"), r.getScore(), "1 - 0.5");
            Map<Integer, Character> allWrong = new HashMap<Integer, Character>();
            for (int i = 1; i <= 4; i++) allWrong.put(i, 'A');
            allWrong.put(1, 'D');
            Result floor = ev.evaluate(keyList, allWrong, new BigDecimal("3"), pass40);
            assertEquals(new BigDecimal("0.00"), floor.getScore(), "score floored at zero");
        });
        test("pass mark boundary is inclusive and uses the exact ratio", () -> {
            Map<Integer, Character> a = new HashMap<Integer, Character>();
            a.put(3, 'C');     // 2 of 5 marks = exactly 40%
            assertEquals(ResultStatus.PASS, ev.evaluate(keyList, a, BigDecimal.ZERO, pass40).getStatus(), "exactly 40% passes");
            assertEquals(ResultStatus.FAIL, ev.evaluate(keyList, a, BigDecimal.ZERO, new BigDecimal("40.01")).getStatus(), "just below");
        });
        test("empty question list does not divide by zero", () -> {
            Result r = ev.evaluate(new ArrayList<Question>(), new HashMap<Integer, Character>(), BigDecimal.ZERO, pass40);
            assertEquals(new BigDecimal("0"), r.getPercentage(), "percentage");
            assertEquals(ResultStatus.FAIL, r.getStatus(), "status");
        });
    }

    private static void ranking() {
        section("Leaderboard sorting");
        test("score desc, then percentage, then earlier submission; ranks are 1..n", () -> {
            LocalDateTime t = LocalDateTime.of(2026, 1, 1, 10, 0);
            List<LeaderboardEntry> rows = Arrays.asList(
                    entry(1, "Cara", "7", "70", t.plusMinutes(5), 1),
                    entry(2, "Ben", "9", "90", t.plusMinutes(9), 2),
                    entry(3, "Ann", "7", "70", t.plusMinutes(1), 3),
                    entry(4, "Dan", "7", "75", t.plusMinutes(8), 4));
            List<LeaderboardEntry> ranked = RankingService.rank(rows, true);
            assertEquals("Ben", ranked.get(0).getStudentName(), "rank 1");
            assertEquals("Dan", ranked.get(1).getStudentName(), "higher percentage first");
            assertEquals("Ann", ranked.get(2).getStudentName(), "earlier submission first");
            assertEquals("Cara", ranked.get(3).getStudentName(), "last");
            for (int i = 0; i < ranked.size(); i++) assertEquals(i + 1, ranked.get(i).getRank(), "rank value");
        });
        test("only each student's best attempt is kept", () -> {
            LocalDateTime t = LocalDateTime.of(2026, 1, 1, 10, 0);
            List<LeaderboardEntry> rows = Arrays.asList(entry(1, "Ann", "4", "40", t, 1), entry(1, "Ann", "8", "80", t.plusDays(1), 2),
                    entry(2, "Ben", "6", "60", t, 3));
            List<LeaderboardEntry> ranked = RankingService.rank(rows, true);
            assertEquals(2, ranked.size(), "one row per student");
            assertEquals(Long.valueOf(2), Long.valueOf(ranked.get(0).getAttemptId()), "Ann's best attempt");
        });
    }

    private static void waitingList() {
        section("Waiting list (queue)");
        test("position and admission follow first-come first-served", () -> {
            List<Integer> queue = Arrays.asList(10, 20, 30);
            assertEquals(2, WaitingListService.positionOf(queue, 20), "position");
            assertEquals(0, WaitingListService.positionOf(queue, 99), "not queued");
            assertTrue(WaitingListService.isAdmitted(queue, 10, 1), "head with one free seat");
            assertTrue(!WaitingListService.isAdmitted(queue, 20, 1), "second with one free seat");
            assertTrue(WaitingListService.isAdmitted(queue, 20, 2), "second with two free seats");
            assertTrue(!WaitingListService.isAdmitted(queue, 99, 3), "newcomer cannot jump the queue");
            assertTrue(WaitingListService.isAdmitted(queue, 99, 4), "newcomer when seats exceed the queue");
            assertTrue(!WaitingListService.isAdmitted(new ArrayList<Integer>(), 5, 0), "no free seats");
            assertTrue(WaitingListService.isAdmitted(new ArrayList<Integer>(), 5, 1), "empty queue and a free seat");
        });
    }

    private static void searching() {
        section("Searching");
        test("binary search by id and linear filter", () -> {
            List<Exam> exams = new ArrayList<Exam>();
            for (int id = 1; id <= 99; id += 2) {
                Exam e = new Exam();
                e.setExamId(id);
                e.setTitle("Exam number " + id);
                exams.add(e);
            }
            assertEquals(24, SearchUtil.binarySearchById(exams, 49, Exam::getExamId), "found index");
            assertEquals(-1, SearchUtil.binarySearchById(exams, 50, Exam::getExamId), "missing id");
            assertEquals(-1, SearchUtil.binarySearchById(new ArrayList<Exam>(), 1, Exam::getExamId), "empty list");
            assertEquals(1, SearchUtil.filter(exams, "NUMBER 97", Exam::getTitle).size(), "case-insensitive filter");
            assertEquals(exams.size(), SearchUtil.filter(exams, "  ", Exam::getTitle).size(), "blank keyword returns all");
        });
    }

    private static void adminValidation() {
        section("Exam / question configuration validation");
        test("invalid exam settings are rejected", () -> {
            Exam e = new Exam();
            e.setTitle("T");
            AdminService.validateExam(e);   // defaults are valid
            e.setDurationMinutes(0);
            expectThrows(ValidationException.class, () -> AdminService.validateExam(e));
            e.setDurationMinutes(30);
            e.setTotalQuestions(0);
            expectThrows(ValidationException.class, () -> AdminService.validateExam(e));
            e.setTotalQuestions(10);
            e.setPassingPercentage(new BigDecimal("101"));
            expectThrows(ValidationException.class, () -> AdminService.validateExam(e));
            e.setPassingPercentage(new BigDecimal("50"));
            e.setStartTime(LocalDateTime.of(2026, 5, 2, 10, 0));
            e.setEndTime(LocalDateTime.of(2026, 5, 1, 10, 0));
            expectThrows(ValidationException.class, () -> AdminService.validateExam(e));
            e.setTitle("   ");
            expectThrows(ValidationException.class, () -> AdminService.validateExam(e));
        });
        test("invalid questions are rejected", () -> {
            Question q = new Question();
            q.setExamId(1);
            q.setText("What?");
            q.setOptions(new String[]{"a", "b", "c", "d"});
            q.setCorrectOption('B');
            q.setMarks(BigDecimal.ONE);
            AdminService.validateQuestion(q);
            q.setOptions(new String[]{"a", "b", "c", "A"});
            expectThrows(ValidationException.class, () -> AdminService.validateQuestion(q));
            q.setOptions(new String[]{"a", "b", "c", ""});
            expectThrows(ValidationException.class, () -> AdminService.validateQuestion(q));
            q.setOptions(new String[]{"a", "b", "c", "d"});
            q.setCorrectOption('E');
            expectThrows(ValidationException.class, () -> AdminService.validateQuestion(q));
            q.setCorrectOption('A');
            q.setMarks(BigDecimal.ZERO);
            expectThrows(ValidationException.class, () -> AdminService.validateQuestion(q));
        });
    }

    private static void accessControl() {
        section("Role checks (student cannot use admin functions)");
        final UserSession student = new UserSession(Role.STUDENT, 1, "Student");
        final UserSession admin = new UserSession(Role.ADMIN, 1, "Admin");
        test("student is denied every admin service call", () -> {
            final AdminService svc = new AdminService();
            expectThrows(AccessDeniedException.class, () -> svc.listExams(student));
            expectThrows(AccessDeniedException.class, () -> svc.saveExam(student, new Exam()));
            expectThrows(AccessDeniedException.class, () -> svc.deleteExam(student, 1));
            expectThrows(AccessDeniedException.class, () -> svc.setExamActive(student, 1, true));
            expectThrows(AccessDeniedException.class, () -> svc.listQuestions(student, 1));
            expectThrows(AccessDeniedException.class, () -> svc.saveQuestion(student, new Question()));
            expectThrows(AccessDeniedException.class, () -> svc.deleteQuestion(student, 1));
            expectThrows(AccessDeniedException.class, () -> svc.listAttempts(student, null));
            expectThrows(AccessDeniedException.class, () -> svc.searchStudents(student, ""));
            expectThrows(AccessDeniedException.class, () -> new ExamService().finalizeAllExpired(student));
        });
        test("admin is denied student-only exam actions", () -> {
            final ExamService svc = new ExamService();
            expectThrows(AccessDeniedException.class, () -> svc.startOrResume(admin, 1));
            expectThrows(AccessDeniedException.class, () -> svc.saveAnswer(admin, 1, 1, 'A'));
            expectThrows(AccessDeniedException.class, () -> svc.submit(admin, 1, null));
            expectThrows(AccessDeniedException.class, () -> svc.getReview(admin, 1));
        });
    }

    private static void databaseFailure() {
        section("Database failure handling");
        test("unreachable database gives a controlled error and nothing is reported as saved", () -> {
            AppConfig.override("jdbc:mysql://127.0.0.1:1/online_exam?connectTimeout=1000", "nobody", "wrong");
            try {
                final UserSession student = new UserSession(Role.STUDENT, 1, "S");
                ServiceException e = expectThrows(ServiceException.class, () -> new ExamService().saveAnswer(student, 1, 1, 'A'));
                String m = e.getMessage();
                assertTrue(m != null && !m.isEmpty(), "has a message");
                assertTrue(!m.contains("Exception") && !m.contains("java.sql") && !m.contains("jdbc:"),
                        "raw database details must not leak: " + m);
                ServiceException reg = expectThrows(ServiceException.class,
                        () -> new AuthService().registerStudent("Test User", "x@example.com", "Passw0rdOK", "Passw0rdOK"));
                assertTrue(!reg.getMessage().toLowerCase().contains("created"), "must not claim success: " + reg.getMessage());
            } finally {
                AppConfig.reset();
            }
        });
        test("administrator registration checks input and the registration key before touching the database", () -> {
            AppConfig.overrideAdminKey("unit-test-key");
            try {
                final AuthService auth = new AuthService();
                expectThrows(ValidationException.class, () -> auth.registerAdmin("", "Passw0rdOK", "Passw0rdOK", "unit-test-key"));
                expectThrows(ValidationException.class, () -> auth.registerAdmin("boss", "weak", "weak", "unit-test-key"));
                expectThrows(ValidationException.class, () -> auth.registerAdmin("boss", "Passw0rdOK", "Different1", "unit-test-key"));
                expectThrows(ValidationException.class, () -> auth.registerAdmin("boss", "Passw0rdOK", "Passw0rdOK", " "));
                ServiceException wrong = expectThrows(ServiceException.class,
                        () -> auth.registerAdmin("boss", "Passw0rdOK", "Passw0rdOK", "not-the-key"));
                assertTrue(wrong.getMessage().startsWith("Invalid administrator registration key"), wrong.getMessage());
                for (int i = 0; i < 5; i++) {
                    try { auth.registerAdmin("boss", "Passw0rdOK", "Passw0rdOK", "still-wrong"); } catch (ServiceException ignored) { }
                }
                ServiceException locked = expectThrows(ServiceException.class,
                        () -> auth.registerAdmin("boss", "Passw0rdOK", "Passw0rdOK", "unit-test-key"));
                assertTrue(locked.getMessage().startsWith("Too many failed attempts"), "throttled: " + locked.getMessage());
            } finally {
                AppConfig.overrideAdminKey(null);
            }
        });
        test("login with empty credentials is rejected before touching the database", () -> {
            expectThrows(ValidationException.class, () -> new AuthService().login(Role.STUDENT, "", ""));
        });
    }
}
