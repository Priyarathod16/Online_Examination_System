package com.onlineexam.web;

import com.onlineexam.dao.DBConnection;
import com.onlineexam.exception.AccessDeniedException;
import com.onlineexam.exception.ExamClosedException;
import com.onlineexam.exception.ExamFullException;
import com.onlineexam.exception.ServiceException;
import com.onlineexam.exception.ValidationException;
import com.onlineexam.model.AttemptQuestion;
import com.onlineexam.model.AttemptStatus;
import com.onlineexam.model.AttemptSummary;
import com.onlineexam.model.Exam;
import com.onlineexam.model.ExamAttempt;
import com.onlineexam.model.HistoryEntry;
import com.onlineexam.model.LeaderboardEntry;
import com.onlineexam.model.Question;
import com.onlineexam.model.Result;
import com.onlineexam.model.ReviewItem;
import com.onlineexam.model.Role;
import com.onlineexam.model.Student;
import com.onlineexam.service.AdminService;
import com.onlineexam.service.AuthService;
import com.onlineexam.service.ExamService;
import com.onlineexam.service.UserSession;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JSON API under /api. It is only an adapter: authentication state lives in {@link SessionStore}, and every rule
 * (roles, deadlines, validation, transactions, answer-key protection) is enforced by the service layer that the desktop
 * application also uses.
 *
 * Browser protections: sessions use an HttpOnly + SameSite=Strict cookie; every state-changing request must carry the
 * header "X-Requested-With" and a JSON content type (a cross-site form or fetch cannot send these without a CORS
 * preflight, which this server never grants).
 */
public final class ApiHandler implements HttpHandler {
    private static final Logger LOG = Logger.getLogger(ApiHandler.class.getName());
    private static final String COOKIE = "EXAMSESSION";
    private static final int MAX_BODY = 1000000;

    private final AuthService auth = new AuthService();
    private final AdminService admin = new AdminService();
    private final ExamService exams = new ExamService();
    private final SessionStore sessions;
    private final boolean secureCookie;

    public ApiHandler(SessionStore sessions, boolean secureCookie) {
        this.sessions = sessions;
        this.secureCookie = secureCookie;
    }

    // ----------------------------------------------------------------- plumbing

    private static final class ApiException extends Exception {
        private static final long serialVersionUID = 1L;
        final int status;
        final String code;

        ApiException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    /** One request: path segments after /api/, query string, lazily parsed JSON body, session. */
    private final class Req {
        final HttpExchange ex;
        final String method;
        final String[] seg;
        final Map<String, String> query;
        private Body body;
        String token;
        UserSession session;

        Req(HttpExchange ex, String method, String[] seg, Map<String, String> query) {
            this.ex = ex;
            this.method = method;
            this.seg = seg;
            this.query = query;
        }

        Body body() throws ApiException, ValidationException, IOException {
            if (body == null) body = new Body(readJson(ex));
            return body;
        }

        UserSession user() throws ApiException {
            if (session == null) throw new ApiException(401, "unauthorized", "Please log in to continue.");
            return session;
        }

        void expect(String... methods) throws ApiException {
            for (String m : methods) if (m.equals(method)) return;
            throw new ApiException(405, "method", "Method not allowed.");
        }
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        int status = 200;
        Object payload;
        try {
            String method = ex.getRequestMethod();
            String raw = ex.getRequestURI().getPath();
            String rest = raw.length() > 5 ? raw.substring(5) : "";          // strip "/api/"
            String[] seg = rest.isEmpty() ? new String[0] : rest.split("/");
            for (int i = 0; i < seg.length; i++) seg[i] = URLDecoder.decode(seg[i], "UTF-8");
            if (!"GET".equals(method) && !"HEAD".equals(method)
                    && (ex.getRequestHeaders().getFirst("X-Requested-With") == null)) {
                throw new ApiException(403, "forbidden", "Missing X-Requested-With header.");
            }
            Req req = new Req(ex, method, seg, parseQuery(ex.getRequestURI().getRawQuery()));
            req.token = cookie(ex, COOKIE);
            req.session = sessions.get(req.token);
            payload = route(req);
        } catch (ApiException e) {
            status = e.status;
            payload = error(e.code, e.getMessage());
        } catch (ValidationException e) {
            status = 400;
            payload = error("validation", e.getMessage());
        } catch (AccessDeniedException e) {
            status = 403;
            payload = error("forbidden", e.getMessage());
        } catch (ExamClosedException e) {
            status = 409;
            payload = error("closed", e.getMessage());
        } catch (ExamFullException e) {
            status = 409;
            payload = error("full", e.getMessage());
        } catch (ServiceException e) {
            boolean db = e.getCause() instanceof SQLException;
            status = db ? 503 : 500;
            payload = error(db ? "database" : "server", e.getMessage());
        } catch (IllegalArgumentException e) {
            status = 400;
            payload = error("validation", "The request address is malformed.");
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Unexpected error while handling " + ex.getRequestURI(), e);
            status = 500;
            payload = error("server", "Something unexpected went wrong. Please try again.");
        }
        try {
            byte[] bytes = Json.write(payload == null ? ok() : payload).getBytes("UTF-8");
            StaticHandler.securityHeaders(ex);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        } finally {
            ex.close();
        }
    }

    private static Map<String, Object> ok() { return map("ok", Boolean.TRUE); }

    private static Map<String, Object> error(String code, String message) { return map("error", message, "code", code); }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Map<String, String> parseQuery(String raw) throws IOException {
        Map<String, String> q = new HashMap<String, String>();
        if (raw == null || raw.isEmpty()) return q;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), "UTF-8");
            String v = i < 0 ? "" : URLDecoder.decode(pair.substring(i + 1), "UTF-8");
            q.put(k, v);
        }
        return q;
    }

    private static String cookie(HttpExchange ex, String name) {
        List<String> headers = ex.getRequestHeaders().get("Cookie");
        if (headers == null) return null;
        for (String h : headers) {
            for (String part : h.split(";")) {
                String p = part.trim();
                if (p.startsWith(name + "=")) return p.substring(name.length() + 1);
            }
        }
        return null;
    }

    private static Map<String, Object> readJson(HttpExchange ex) throws ApiException, ValidationException, IOException {
        String type = ex.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.toLowerCase().startsWith("application/json")) {
            throw new ApiException(415, "validation", "Requests with a body must use Content-Type: application/json.");
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (InputStream in = ex.getRequestBody()) {
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
                if (buf.size() > MAX_BODY) throw new ApiException(413, "validation", "The request is too large.");
            }
        }
        Object parsed = Json.parse(new String(buf.toByteArray(), "UTF-8"));
        if (!(parsed instanceof Map)) throw new ValidationException("The request body must be a JSON object.");
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) parsed;
        return m;
    }

    // ------------------------------------------------------------------ routing

    private Object route(Req r) throws ApiException, ServiceException, IOException {
        if (r.seg.length == 0) throw new ApiException(404, "notfound", "Unknown API endpoint.");
        switch (r.seg[0]) {
            case "health": r.expect("GET"); return health();
            case "login": r.expect("POST"); return login(r);
            case "register": r.expect("POST"); return register(r);
            case "register-admin": r.expect("POST"); return registerAdmin(r);
            case "logout": r.expect("POST"); return logout(r);
            case "me": r.expect("GET"); return me(r);
            case "student": return student(r);
            case "admin": return adminRoutes(r);
            case "leaderboard": return leaderboard(r);
            default: throw new ApiException(404, "notfound", "Unknown API endpoint.");
        }
    }

    private Object health() {
        String problem = DBConnection.checkAvailability();
        return map("database", problem == null, "message", problem == null ? "Database connection OK" : problem);
    }

    // ----------------------------------------------------------------- session

    private Object login(Req r) throws ApiException, ServiceException, IOException {
        Body b = r.body();
        String roleText = b.str("role");
        Role role = "admin".equalsIgnoreCase(roleText) ? Role.ADMIN : Role.STUDENT;
        UserSession session;
        try {
            session = auth.login(role, b.str("identifier"), b.str("password"));
        } catch (ValidationException e) {
            throw e;
        } catch (ServiceException e) {
            if (e.getCause() != null) throw e;                       // database problem
            boolean locked = e.getMessage().startsWith("Too many");
            throw new ApiException(locked ? 429 : 401, locked ? "locked" : "credentials", e.getMessage());
        }
        sessions.remove(r.token);
        String token = sessions.create(session);
        r.ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict"
                + (secureCookie ? "; Secure" : ""));
        return map("role", session.getRole().name().toLowerCase(), "name", session.getDisplayName());
    }

    private Object register(Req r) throws ApiException, ServiceException, IOException {
        Body b = r.body();
        auth.registerStudent(b.str("name"), b.str("email"), b.str("password"), b.str("confirm"));
        return ok();
    }

    private Object registerAdmin(Req r) throws ApiException, ServiceException, IOException {
        Body b = r.body();
        try {
            auth.registerAdmin(b.str("username"), b.str("password"), b.str("confirm"), b.str("adminKey"));
        } catch (ValidationException e) {
            throw e;
        } catch (ServiceException e) {
            if (e.getCause() != null) throw e;                       // database problem
            boolean locked = e.getMessage().startsWith("Too many");
            throw new ApiException(locked ? 429 : 403, locked ? "locked" : "forbidden", e.getMessage());
        }
        return ok();
    }

    private Object logout(Req r) {
        sessions.remove(r.token);
        r.ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
        return ok();
    }

    private Object me(Req r) throws ApiException {
        UserSession s = r.user();
        return map("role", s.getRole().name().toLowerCase(), "name", s.getDisplayName());
    }

    // ------------------------------------------------------------------ student

    private Object student(Req r) throws ApiException, ServiceException, IOException {
        UserSession s = r.user();
        if (r.seg.length < 2) throw new ApiException(404, "notfound", "Unknown API endpoint.");
        String part = r.seg[1];
        if ("history".equals(part)) {
            r.expect("GET");
            List<Object> out = new ArrayList<Object>();
            for (HistoryEntry h : exams.getHistory(s)) out.add(historyMap(h));
            return out;
        }
        if ("exams".equals(part)) {
            if (r.seg.length == 2) {
                r.expect("GET");
                List<Object> out = new ArrayList<Object>();
                for (Exam e : exams.listAvailableExams(s)) out.add(examMap(e, false));
                return out;
            }
            int examId = id(r.seg[2]);
            String action = r.seg.length > 3 ? r.seg[3] : "";
            if ("start".equals(action)) {
                r.expect("POST");
                return attemptMap(exams.startOrResume(s, examId));
            }
            if ("waitlist".equals(action)) {
                r.expect("POST");
                return map("position", Integer.valueOf(exams.joinWaitingList(s, examId)));
            }
            throw new ApiException(404, "notfound", "Unknown API endpoint.");
        }
        if ("attempts".equals(part) && r.seg.length >= 4) {
            long attemptId = longId(r.seg[2]);
            String action = r.seg[3];
            if ("answer".equals(action)) {
                r.expect("PUT");
                Body b = r.body();
                exams.saveAnswer(s, attemptId, b.reqInt("questionId", "Question"), b.optionOrNull("option"));
                return ok();
            }
            if ("mark".equals(action)) {
                r.expect("PUT");
                Body b = r.body();
                exams.setMarkedForReview(s, attemptId, b.reqInt("questionId", "Question"), b.bool("marked", false));
                return ok();
            }
            if ("submit".equals(action)) {
                r.expect("POST");
                return resultMap(exams.submit(s, attemptId, r.body().answers("answers")));
            }
            if ("result".equals(action)) {
                r.expect("GET");
                return resultMap(exams.getResult(s, attemptId));
            }
            if ("review".equals(action)) {
                r.expect("GET");
                List<Object> out = new ArrayList<Object>();
                for (ReviewItem i : exams.getReview(s, attemptId)) out.add(reviewMap(i));
                return out;
            }
        }
        throw new ApiException(404, "notfound", "Unknown API endpoint.");
    }

    private Object leaderboard(Req r) throws ApiException, ServiceException, IOException {
        UserSession s = r.user();
        r.expect("GET");
        if (r.seg.length >= 2 && "exams".equals(r.seg[1])) {
            List<Object> out = new ArrayList<Object>();
            for (Exam e : exams.listLeaderboardExams(s)) out.add(map("id", Integer.valueOf(e.getExamId()), "title", e.getTitle()));
            return out;
        }
        if (r.seg.length < 2) throw new ApiException(404, "notfound", "Unknown API endpoint.");
        List<Object> out = new ArrayList<Object>();
        for (LeaderboardEntry e : exams.getLeaderboard(s, id(r.seg[1]))) out.add(leaderMap(e));
        return out;
    }

    // -------------------------------------------------------------------- admin

    private Object adminRoutes(Req r) throws ApiException, ServiceException, IOException {
        UserSession s = r.user();
        s.requireAdmin();                                   // also re-checked inside every service method
        if (r.seg.length < 2) throw new ApiException(404, "notfound", "Unknown API endpoint.");
        String part = r.seg[1];
        if ("exams".equals(part)) {
            if (r.seg.length == 2) {
                if ("GET".equals(r.method)) {
                    List<Object> out = new ArrayList<Object>();
                    for (Exam e : admin.listExams(s)) out.add(examMap(e, true));
                    return out;
                }
                r.expect("POST");
                return examMap(admin.saveExam(s, examFromBody(r.body(), 0)), true);
            }
            int examId = id(r.seg[2]);
            if (r.seg.length == 3) {
                if ("PUT".equals(r.method)) return examMap(admin.saveExam(s, examFromBody(r.body(), examId)), true);
                r.expect("DELETE");
                admin.deleteExam(s, examId);
                return ok();
            }
            String action = r.seg[3];
            if ("active".equals(action)) {
                r.expect("POST");
                admin.setExamActive(s, examId, r.body().bool("active", false));
                return ok();
            }
            if ("questions".equals(action)) {
                if ("GET".equals(r.method)) {
                    List<Object> out = new ArrayList<Object>();
                    for (Question q : admin.listQuestions(s, examId)) out.add(questionMap(q));
                    return out;
                }
                r.expect("POST");
                return questionMap(admin.saveQuestion(s, questionFromBody(r.body(), examId, 0)));
            }
        }
        if ("questions".equals(part) && r.seg.length == 3) {
            int questionId = id(r.seg[2]);
            if ("PUT".equals(r.method)) {
                Body b = r.body();
                return questionMap(admin.saveQuestion(s, questionFromBody(b, b.reqInt("examId", "Exam"), questionId)));
            }
            r.expect("DELETE");
            admin.deleteQuestion(s, questionId);
            return ok();
        }
        if ("attempts".equals(part)) {
            r.expect("GET");
            String ex = r.query.get("examId");
            Integer examId = ex == null || ex.isEmpty() ? null : Integer.valueOf(id(ex));
            List<Object> out = new ArrayList<Object>();
            for (AttemptSummary a : admin.listAttempts(s, examId)) out.add(attemptSummaryMap(a));
            return out;
        }
        if ("students".equals(part)) {
            r.expect("GET");
            List<Object> out = new ArrayList<Object>();
            for (Student st : admin.searchStudents(s, r.query.containsKey("q") ? r.query.get("q") : "")) out.add(studentMap(st));
            return out;
        }
        throw new ApiException(404, "notfound", "Unknown API endpoint.");
    }

    private static int id(String text) throws ApiException {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new ApiException(404, "notfound", "Unknown identifier.");
        }
    }

    private static long longId(String text) throws ApiException {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new ApiException(404, "notfound", "Unknown identifier.");
        }
    }

    // ------------------------------------------------------- JSON -> model

    private static Exam examFromBody(Body b, int examId) throws ValidationException {
        Exam e = new Exam();
        e.setExamId(examId);
        e.setTitle(b.str("title"));
        e.setDescription(b.str("description"));
        e.setInstructions(b.str("instructions"));
        e.setDurationMinutes(b.reqInt("durationMinutes", "Duration"));
        e.setTotalQuestions(b.reqInt("totalQuestions", "Questions per attempt"));
        e.setPassingPercentage(b.reqDecimal("passingPercentage", "Passing percentage"));
        e.setNegativeMarks(b.decimalOr("negativeMarks", "Negative marks", BigDecimal.ZERO));
        e.setStartTime(b.dateTime("startTime", "Available from"));
        e.setEndTime(b.dateTime("endTime", "Available until"));
        e.setActive(b.bool("active", false));
        e.setShuffleQuestions(b.bool("shuffleQuestions", true));
        e.setShuffleOptions(b.bool("shuffleOptions", true));
        e.setAllowReview(b.bool("allowReview", false));
        e.setMaxAttempts(b.reqInt("maxAttempts", "Maximum attempts"));
        e.setMaxConcurrent(b.optInt("maxConcurrent", "Max. students at once"));
        return e;
    }

    private static Question questionFromBody(Body b, int examId, int questionId) throws ValidationException {
        Question q = new Question();
        q.setQuestionId(questionId);
        q.setExamId(examId);
        q.setText(b.str("text"));
        List<Object> opts = b.list("options");
        String[] options = new String[Question.OPTION_COUNT];
        for (int i = 0; i < options.length; i++) {
            Object o = opts != null && i < opts.size() ? opts.get(i) : null;
            options[i] = o instanceof String ? (String) o : null;
        }
        q.setOptions(options);
        String correct = b.str("correct");
        q.setCorrectOption(correct != null && correct.length() == 1 ? correct.toUpperCase().charAt(0) : '?');
        q.setMarks(b.reqDecimal("marks", "Marks"));
        return q;
    }

    // ------------------------------------------------------- model -> JSON

    private static String time(LocalDateTime t) { return t == null ? null : t.toString(); }

    private static Map<String, Object> examMap(Exam e, boolean forAdmin) {
        Map<String, Object> m = map("id", Integer.valueOf(e.getExamId()), "title", e.getTitle(), "description", e.getDescription(),
                "instructions", e.getInstructions(), "durationMinutes", Integer.valueOf(e.getDurationMinutes()),
                "totalQuestions", Integer.valueOf(e.getTotalQuestions()), "passingPercentage", e.getPassingPercentage(),
                "negativeMarks", e.getNegativeMarks(), "startTime", time(e.getStartTime()), "endTime", time(e.getEndTime()),
                "active", Boolean.valueOf(e.isActive()), "shuffleQuestions", Boolean.valueOf(e.isShuffleQuestions()),
                "shuffleOptions", Boolean.valueOf(e.isShuffleOptions()), "allowReview", Boolean.valueOf(e.isAllowReview()),
                "maxAttempts", Integer.valueOf(e.getMaxAttempts()), "maxConcurrent", e.getMaxConcurrent());
        if (forAdmin) {
            m.put("questionCount", Integer.valueOf(e.getQuestionCount()));
        } else {
            m.put("attemptsUsed", Integer.valueOf(e.getAttemptsUsed()));
            m.put("activeAttemptId", e.getActiveAttemptId());
        }
        return m;
    }

    /** Student view of an attempt: questions without any answer key. */
    private static Map<String, Object> attemptMap(ExamAttempt a) {
        List<Object> qs = new ArrayList<Object>();
        for (AttemptQuestion q : a.getQuestions()) {
            List<Object> opts = new ArrayList<Object>();
            for (int i = 0; i < q.getOptionCount(); i++) {
                opts.add(map("letter", String.valueOf(q.getOptionLetter(i)), "text", q.getOptionText(i)));
            }
            qs.add(map("id", Integer.valueOf(q.getQuestionId()), "text", q.getText(), "marks", q.getMarks(), "options", opts));
        }
        Map<String, Object> answers = new LinkedHashMap<String, Object>();
        for (Map.Entry<Integer, Character> e : a.getAnswers().entrySet()) answers.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
        return map("attemptId", Long.valueOf(a.getAttemptId()), "examTitle", a.getExamTitle(), "attemptNo", Integer.valueOf(a.getAttemptNo()),
                "remainingSeconds", Long.valueOf(a.getRemainingSeconds()), "questions", qs, "answers", answers,
                "marked", new ArrayList<Object>(a.getMarked()));
    }

    private static Map<String, Object> resultMap(Result r) {
        return map("attemptId", Long.valueOf(r.getAttemptId()), "examId", Integer.valueOf(r.getExamId()), "examTitle", r.getExamTitle(),
                "score", r.getScore(), "maxScore", r.getMaxScore(), "percentage", r.getPercentage(), "status", r.getStatus().name(),
                "timedOut", Boolean.valueOf(r.getAttemptStatus() == AttemptStatus.TIMED_OUT),
                "reviewAllowed", Boolean.valueOf(r.isReviewAllowed()));
    }

    private static Map<String, Object> historyMap(HistoryEntry h) {
        return map("attemptId", Long.valueOf(h.getAttemptId()), "examId", Integer.valueOf(h.getExamId()), "examTitle", h.getExamTitle(),
                "attemptNo", Integer.valueOf(h.getAttemptNo()), "score", h.getScore(), "maxScore", h.getMaxScore(),
                "percentage", h.getPercentage(), "status", h.getStatus().name(),
                "timedOut", Boolean.valueOf(h.getAttemptStatus() == AttemptStatus.TIMED_OUT),
                "submittedAt", time(h.getSubmittedAt()), "reviewAllowed", Boolean.valueOf(h.isReviewAllowed()));
    }

    private static Map<String, Object> leaderMap(LeaderboardEntry e) {
        return map("rank", Integer.valueOf(e.getRank()), "student", e.getStudentName(), "score", e.getScore(), "maxScore", e.getMaxScore(),
                "percentage", e.getPercentage(), "status", e.getStatus().name(), "submittedAt", time(e.getSubmittedAt()));
    }

    private static Map<String, Object> reviewMap(ReviewItem i) {
        List<Object> opts = new ArrayList<Object>();
        for (int k = 0; k < i.getOptionTexts().length; k++) {
            opts.add(map("letter", String.valueOf(i.getOptionLetters()[k]), "text", i.getOptionTexts()[k]));
        }
        return map("order", Integer.valueOf(i.getDisplayOrder()), "text", i.getText(), "options", opts,
                "selected", i.getSelected() == null ? null : String.valueOf(i.getSelected()), "correct", String.valueOf(i.getCorrect()),
                "marks", i.getMarks(), "isCorrect", Boolean.valueOf(i.isCorrect()));
    }

    /** Admin view of a question (includes the answer key). */
    private static Map<String, Object> questionMap(Question q) {
        List<Object> opts = new ArrayList<Object>();
        for (String o : q.getOptions()) opts.add(o);
        return map("id", Integer.valueOf(q.getQuestionId()), "examId", Integer.valueOf(q.getExamId()), "text", q.getText(),
                "options", opts, "correct", String.valueOf(q.getCorrectOption()), "marks", q.getMarks());
    }

    private static Map<String, Object> attemptSummaryMap(AttemptSummary a) {
        return map("attemptId", Long.valueOf(a.getAttemptId()), "student", a.getStudentName(), "email", a.getStudentEmail(),
                "examTitle", a.getExamTitle(), "attemptNo", Integer.valueOf(a.getAttemptNo()), "startedAt", time(a.getStartedAt()),
                "submittedAt", time(a.getSubmittedAt()), "status", a.getStatus().name(), "score", a.getScore(),
                "maxScore", a.getMaxScore(), "percentage", a.getPercentage(),
                "result", a.getResultStatus() == null ? null : a.getResultStatus().name());
    }

    private static Map<String, Object> studentMap(Student s) {
        return map("id", Integer.valueOf(s.getStudentId()), "name", s.getName(), "email", s.getEmail(),
                "registeredAt", time(s.getCreatedAt()), "attempts", Integer.valueOf(s.getAttemptCount()));
    }

    // --------------------------------------------------------------- body helper

    /** Typed access to a parsed JSON object that reports problems as friendly validation errors. */
    static final class Body {
        private final Map<String, Object> m;

        Body(Map<String, Object> m) { this.m = m; }

        String str(String key) throws ValidationException {
            Object v = m.get(key);
            if (v == null) return null;
            if (!(v instanceof String)) throw new ValidationException("'" + key + "' must be text.");
            return (String) v;
        }

        Integer optInt(String key, String label) throws ValidationException {
            Object v = m.get(key);
            if (v == null || (v instanceof String && ((String) v).trim().isEmpty())) return null;
            return Integer.valueOf(toInt(v, label));
        }

        int reqInt(String key, String label) throws ValidationException {
            Object v = m.get(key);
            if (v == null) throw new ValidationException(label + " is required.");
            return toInt(v, label);
        }

        private static int toInt(Object v, String label) throws ValidationException {
            if (v instanceof BigDecimal) {
                try {
                    return ((BigDecimal) v).intValueExact();
                } catch (ArithmeticException e) {
                    throw new ValidationException(label + " must be a whole number.");
                }
            }
            if (v instanceof String) return com.onlineexam.util.ValidationUtil.parseInt((String) v, label);
            throw new ValidationException(label + " must be a whole number.");
        }

        BigDecimal reqDecimal(String key, String label) throws ValidationException {
            Object v = m.get(key);
            if (v == null || (v instanceof String && ((String) v).trim().isEmpty())) throw new ValidationException(label + " is required.");
            return toDecimal(v, label);
        }

        BigDecimal decimalOr(String key, String label, BigDecimal fallback) throws ValidationException {
            Object v = m.get(key);
            if (v == null || (v instanceof String && ((String) v).trim().isEmpty())) return fallback;
            return toDecimal(v, label);
        }

        private static BigDecimal toDecimal(Object v, String label) throws ValidationException {
            if (v instanceof BigDecimal) {
                BigDecimal d = (BigDecimal) v;
                if (d.stripTrailingZeros().scale() > 2) throw new ValidationException(label + " can have at most 2 decimal places.");
                return d;
            }
            if (v instanceof String) return com.onlineexam.util.ValidationUtil.parseDecimal((String) v, label);
            throw new ValidationException(label + " must be a number.");
        }

        boolean bool(String key, boolean fallback) throws ValidationException {
            Object v = m.get(key);
            if (v == null) return fallback;
            if (!(v instanceof Boolean)) throw new ValidationException("'" + key + "' must be true or false.");
            return ((Boolean) v).booleanValue();
        }

        LocalDateTime dateTime(String key, String label) throws ValidationException {
            String v = str(key);
            if (v == null || v.trim().isEmpty()) return null;
            try {
                return LocalDateTime.parse(v.trim());
            } catch (DateTimeParseException e) {
                throw new ValidationException(label + " is not a valid date and time.");
            }
        }

        @SuppressWarnings("unchecked")
        List<Object> list(String key) throws ValidationException {
            Object v = m.get(key);
            if (v == null) return null;
            if (!(v instanceof List)) throw new ValidationException("'" + key + "' must be a list.");
            return (List<Object>) v;
        }

        /** An option letter A-D, or null (meaning "clear the answer"). */
        Character optionOrNull(String key) throws ValidationException {
            Object v = m.get(key);
            if (v == null) return null;
            if (!(v instanceof String) || ((String) v).length() != 1) throw new ValidationException("Invalid answer option.");
            return Character.valueOf(((String) v).toUpperCase().charAt(0));
        }

        /** Optional map of question id -> option letter. */
        Map<Integer, Character> answers(String key) throws ValidationException {
            Map<Integer, Character> out = new HashMap<Integer, Character>();
            Object v = m.get(key);
            if (v == null) return out;
            if (!(v instanceof Map)) throw new ValidationException("'" + key + "' must be an object.");
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                try {
                    Object letter = e.getValue();
                    if (letter == null) continue;
                    if (!(letter instanceof String) || ((String) letter).length() != 1) throw new ValidationException("Invalid answer option.");
                    out.put(Integer.valueOf(Integer.parseInt(String.valueOf(e.getKey()))), Character.valueOf(((String) letter).toUpperCase().charAt(0)));
                } catch (NumberFormatException ex) {
                    throw new ValidationException("Invalid question id in answers.");
                }
            }
            return out;
        }
    }
}
