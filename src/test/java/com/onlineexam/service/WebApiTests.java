package com.onlineexam.service;

import com.onlineexam.model.Role;
import com.onlineexam.web.Json;
import com.onlineexam.web.SessionStore;
import com.onlineexam.web.WebServer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.onlineexam.service.Harness.assertEquals;
import static com.onlineexam.service.Harness.assertTrue;
import static com.onlineexam.service.Harness.expectThrows;
import static com.onlineexam.service.Harness.section;
import static com.onlineexam.service.Harness.test;

/**
 * Tests the real HTTP server (static files, security headers, session/role handling, validation) on a random port.
 * No database is needed: every case here is decided before any SQL would run. Run from the project folder.
 */
public final class WebApiTests {
    private static WebServer server;
    private static String base;
    private static String studentToken;
    private static String adminToken;

    private static final class Resp {
        int status;
        String body;
        Map<String, List<String>> headers;

        String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return String.join(",", e.getValue());
            }
            return null;
        }
    }

    private WebApiTests() { }

    private static Resp call(String method, String path, String body, String token, boolean xhr, String contentType) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setRequestMethod(method);
        c.setInstanceFollowRedirects(false);
        if (xhr) c.setRequestProperty("X-Requested-With", "XMLHttpRequest");
        if (token != null) c.setRequestProperty("Cookie", "EXAMSESSION=" + token);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", contentType);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.getBytes("UTF-8"));
            }
        }
        Resp r = new Resp();
        r.status = c.getResponseCode();
        r.headers = c.getHeaderFields();
        InputStream in = r.status >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        if (in != null) {
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
        }
        r.body = new String(buf.toByteArray(), "UTF-8");
        return r;
    }

    private static Resp get(String path) throws Exception { return call("GET", path, null, null, false, null); }

    private static Resp api(String method, String path, String json, String token) throws Exception {
        return call(method, path, json, token, true, "application/json");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(Resp r) throws Exception { return (Map<String, Object>) Json.parse(r.body); }

    private static String errorOf(Resp r) throws Exception { return String.valueOf(json(r).get("error")); }

    public static void main(String[] args) throws Exception {
        server = new WebServer("127.0.0.1", 0, Paths.get("web"), false);
        server.start();
        base = "http://127.0.0.1:" + server.getPort();
        SessionStore sessions = server.getSessions();
        studentToken = sessions.create(new UserSession(Role.STUDENT, 1, "Test Student"));
        adminToken = sessions.create(new UserSession(Role.ADMIN, 1, "Test Admin"));
        try {
            jsonTests();
            staticTests();
            apiTests();
            roleTests();
            validationTests();
        } finally {
            server.stop();
        }
        System.exit(Harness.summary());
    }

    // ------------------------------------------------------------------------

    private static void jsonTests() {
        section("JSON reader/writer");
        test("round trip with unicode, quotes and nesting", () -> {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("text", "He said \"hi\"\n\tüñí \u20ac");
            m.put("n", new java.math.BigDecimal("12.50"));
            m.put("list", java.util.Arrays.asList(1, true, null, "x"));
            Object back = Json.parse(Json.write(m));
            assertEquals("He said \"hi\"\n\tüñí \u20ac", ((Map<?, ?>) back).get("text"), "text");
            assertEquals(4, ((List<?>) ((Map<?, ?>) back).get("list")).size(), "list size");
        });
        test("angle brackets and ampersands are escaped in output", () -> {
            String out = Json.write("<script>alert(1)</script> & more");
            assertTrue(!out.contains("<") && !out.contains(">") && !out.contains("&"), out);
            assertEquals("<script>alert(1)</script> & more", Json.parse(out), "decodes back");
        });
        test("malformed JSON is rejected with a friendly message", () -> {
            for (String bad : new String[]{"{", "{\"a\":}", "[1,]", "{\"a\":1,}", "\"unterminated", "{'a':1}", "nul", "{\"a\":1} x", ""}) {
                expectThrows(com.onlineexam.exception.ValidationException.class, () -> Json.parse(bad));
            }
        });
        test("absurdly deep nesting is rejected", () -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 500; i++) sb.append('[');
            final String deep = sb.toString();
            expectThrows(com.onlineexam.exception.ValidationException.class, () -> Json.parse(deep));
        });
        test("session tokens are long, random and revocable", () -> {
            SessionStore s = new SessionStore();
            UserSession u = new UserSession(Role.STUDENT, 5, "x");
            String a = s.create(u);
            String b = s.create(u);
            assertTrue(a.length() == 64 && !a.equals(b), "token shape");
            assertTrue(s.get(a) == u && s.get("nope") == null && s.get(null) == null, "lookup");
            s.remove(a);
            assertTrue(s.get(a) == null, "removed");
        });
    }

    private static void staticTests() {
        section("Website files and security headers");
        test("GET /index.html serves the page with a strict Content-Security-Policy", () -> {
            Resp r = get("/index.html");
            assertEquals(200, r.status, "status");
            assertTrue(r.header("Content-Type").startsWith("text/html"), "content type");
            assertTrue(r.body.contains("<script type=\"module\" src=\"js/app.js\">"), "loads the app");
            assertTrue(r.header("Content-Security-Policy").contains("script-src 'self'"), "CSP");
            assertEquals("nosniff", r.header("X-Content-Type-Options"), "nosniff");
            assertEquals("DENY", r.header("X-Frame-Options"), "frame options");
            assertTrue(!r.body.contains("onclick=") && !r.body.contains("<script>"), "no inline scripts (CSP-safe)");
        });
        test("GET / serves index.html; css and js have the right content types", () -> {
            assertTrue(get("/").body.contains("Online Examination System"), "root");
            assertTrue(get("/css/style.css").header("Content-Type").startsWith("text/css"), "css");
            assertTrue(get("/js/app.js").header("Content-Type").startsWith("text/javascript"), "js");
            assertTrue(get("/js/views/admin.js").status == 200, "nested js");
        });
        test("path traversal and hidden files are refused", () -> {
            assertEquals(403, get("/%2e%2e/pom.xml").status, "encoded ..");
            assertEquals(403, get("/..%2fconfig/db.properties.example").status, "encoded ../");
            assertTrue(get("/config/db.properties").status == 404, "config folder is outside the web root");
            assertEquals(400, get("/js%5c..%5cindex.html").status, "backslash");
        });
        test("unknown files give 404, other methods 405", () -> {
            assertEquals(404, get("/missing.js").status, "404");
            assertEquals(405, call("POST", "/index.html", "x", null, true, "text/plain").status, "405");
        });
    }

    private static void apiTests() {
        section("API: authentication and request hygiene");
        test("protected endpoints return 401 JSON without a session", () -> {
            for (String p : new String[]{"/api/me", "/api/student/exams", "/api/student/history", "/api/admin/exams", "/api/leaderboard/exams"}) {
                Resp r = api("GET", p, null, null);
                assertEquals(401, r.status, p);
                assertEquals("unauthorized", json(r).get("code"), p);
                assertEquals("no-store", r.header("Cache-Control"), "no caching of API data");
            }
        });
        test("state-changing requests need the X-Requested-With header (CSRF guard)", () -> {
            Resp r = call("POST", "/api/logout", "{}", studentToken, false, "application/json");
            assertEquals(403, r.status, "status");
        });
        test("request bodies must be JSON", () -> {
            Resp r = call("POST", "/api/login", "a=b", null, true, "application/x-www-form-urlencoded");
            assertEquals(415, r.status, "status");
            assertEquals(400, api("POST", "/api/login", "{not json", null).status, "invalid JSON");
            assertEquals(400, api("POST", "/api/login", "[1,2]", null).status, "array instead of object");
        });
        test("login with empty credentials gives a validation error, not a stack trace", () -> {
            Resp r = api("POST", "/api/login", "{\"role\":\"student\",\"identifier\":\"\",\"password\":\"\"}", null);
            assertEquals(400, r.status, "status");
            assertTrue(errorOf(r).contains("email and password"), errorOf(r));
            assertTrue(!r.body.contains("at com.") && !r.body.contains("Exception"), "no internals leaked");
        });
        test("administrator registration: validation errors are 400, a wrong key is 403, and no session is created", () -> {
            com.onlineexam.util.AppConfig.overrideAdminKey("web-test-key");
            try {
                Resp bad = api("POST", "/api/register-admin",
                        "{\"username\":\"x\",\"password\":\"Passw0rd1\",\"confirm\":\"Passw0rd1\",\"adminKey\":\"web-test-key\"}", null);
                assertEquals(400, bad.status, "short username " + bad.body);
                Resp mismatch = api("POST", "/api/register-admin",
                        "{\"username\":\"newadmin\",\"password\":\"Passw0rd1\",\"confirm\":\"Passw0rd2\",\"adminKey\":\"web-test-key\"}", null);
                assertEquals(400, mismatch.status, "mismatch");
                Resp missingKey = api("POST", "/api/register-admin",
                        "{\"username\":\"newadmin\",\"password\":\"Passw0rd1\",\"confirm\":\"Passw0rd1\"}", null);
                assertEquals(400, missingKey.status, "missing key");
                Resp wrong = api("POST", "/api/register-admin",
                        "{\"username\":\"newadmin\",\"password\":\"Passw0rd1\",\"confirm\":\"Passw0rd1\",\"adminKey\":\"guess\"}", null);
                assertEquals(403, wrong.status, "wrong key " + wrong.body);
                assertEquals(null, wrong.header("Set-Cookie"), "no cookie");
                assertTrue(!wrong.body.contains("web-test-key"), "the real key is never echoed");
            } finally {
                com.onlineexam.util.AppConfig.overrideAdminKey(null);
            }
        });
        test("login while the database is unreachable returns a controlled 503 and no session cookie", () -> {
            com.onlineexam.util.AppConfig.override("jdbc:mysql://127.0.0.1:1/online_exam?connectTimeout=1000", "x", "y");
            try {
                Resp r = api("POST", "/api/login", "{\"role\":\"student\",\"identifier\":\"a@b.co\",\"password\":\"Passw0rd1\"}", null);
                assertEquals(503, r.status, "status " + r.body);
                assertEquals(null, r.header("Set-Cookie"), "no cookie");
                assertTrue(!r.body.contains("jdbc:") && !r.body.contains("Exception"), r.body);
            } finally {
                com.onlineexam.util.AppConfig.reset();
            }
        });
        test("session cookie lifecycle: /api/me works with a session and logout revokes it", () -> {
            Resp me = api("GET", "/api/me", null, studentToken);
            assertEquals(200, me.status, "me");
            assertEquals("student", json(me).get("role"), "role");
            String temp = server.getSessions().create(new UserSession(Role.STUDENT, 9, "Temp"));
            Resp out = api("POST", "/api/logout", "{}", temp);
            assertEquals(200, out.status, "logout");
            String cookie = out.header("Set-Cookie");
            assertTrue(cookie.contains("HttpOnly") && cookie.contains("SameSite=Strict") && cookie.contains("Max-Age=0"), cookie);
            assertEquals(401, api("GET", "/api/me", null, temp).status, "token no longer valid");
        });
        test("unknown routes and ids are 404", () -> {
            assertEquals(404, api("GET", "/api/nothing", null, studentToken).status, "route");
            assertEquals(404, api("GET", "/api/admin/exams/abc/questions", null, adminToken).status, "bad id");
            assertEquals(404, api("GET", "/api/student/attempts/xyz/result", null, studentToken).status, "bad attempt id");
            assertEquals(405, api("DELETE", "/api/me", null, studentToken).status, "method");
        });
    }

    private static void roleTests() {
        section("API: role separation");
        test("a student session is denied every admin endpoint (403)", () -> {
            String[][] calls = {{"GET", "/api/admin/exams"}, {"POST", "/api/admin/exams"}, {"PUT", "/api/admin/exams/1"},
                    {"DELETE", "/api/admin/exams/1"}, {"POST", "/api/admin/exams/1/active"}, {"GET", "/api/admin/exams/1/questions"},
                    {"POST", "/api/admin/exams/1/questions"}, {"PUT", "/api/admin/questions/1"}, {"DELETE", "/api/admin/questions/1"},
                    {"GET", "/api/admin/attempts"}, {"GET", "/api/admin/students"}};
            for (String[] c : calls) {
                Resp r = api(c[0], c[1], c[0].equals("GET") || c[0].equals("DELETE") ? null : "{}", studentToken);
                assertEquals(403, r.status, c[0] + " " + c[1]);
            }
        });
        test("an admin session is denied student-only endpoints (403)", () -> {
            String[][] calls = {{"GET", "/api/student/exams"}, {"POST", "/api/student/exams/1/start"}, {"PUT", "/api/student/attempts/1/answer"},
                    {"POST", "/api/student/attempts/1/submit"}, {"GET", "/api/student/attempts/1/review"}, {"GET", "/api/student/history"}};
            for (String[] c : calls) {
                Resp r = api(c[0], c[1], c[0].equals("GET") ? null : "{\"questionId\":1,\"option\":\"A\"}", adminToken);
                assertEquals(403, r.status, c[0] + " " + c[1]);
            }
        });
    }

    private static void validationTests() {
        section("API: input validation (decided before any SQL)");
        final String exam = "{\"title\":\"T\",\"durationMinutes\":%s,\"totalQuestions\":10,\"passingPercentage\":40,\"maxAttempts\":1}";
        test("missing or invalid exam fields give 400 with a clear message", () -> {
            assertTrue(errorOf(api("POST", "/api/admin/exams", "{}", adminToken)).contains("Duration"), "missing duration");
            assertTrue(errorOf(api("POST", "/api/admin/exams", String.format(exam, "0"), adminToken)).contains("between 1 and 600"), "range");
            assertTrue(errorOf(api("POST", "/api/admin/exams", String.format(exam, "\"abc\""), adminToken)).contains("whole number"), "text number");
            assertTrue(errorOf(api("POST", "/api/admin/exams", String.format(exam, "1.5"), adminToken)).contains("whole number"), "fraction");
            assertTrue(errorOf(api("POST", "/api/admin/exams", "{\"title\":\"  \",\"durationMinutes\":5,\"totalQuestions\":1,\"passingPercentage\":40,\"maxAttempts\":1}", adminToken)).contains("Title is required"), "blank title");
            assertTrue(errorOf(api("POST", "/api/admin/exams", String.format(exam, "5").replace("\"maxAttempts\":1", "\"maxAttempts\":1,\"startTime\":\"nonsense\""), adminToken)).contains("valid date"), "date");
            assertTrue(errorOf(api("POST", "/api/admin/exams", String.format(exam, "5").replace("\"maxAttempts\":1", "\"maxAttempts\":1,\"startTime\":\"2026-05-02T10:00\",\"endTime\":\"2026-05-01T10:00\""), adminToken)).contains("after the start"), "window");
        });
        test("a valid exam passes validation and then fails cleanly because no database is configured (503)", () -> {
            Resp r = api("POST", "/api/admin/exams", String.format(exam, "30"), adminToken);
            assertEquals(503, r.status, r.body);
            assertEquals("database", json(r).get("code"), "code");
            assertTrue(!r.body.contains("Exception") && !r.body.contains("jdbc"), "no internals: " + r.body);
        });
        test("invalid questions are rejected with 400", () -> {
            String dup = "{\"text\":\"Q?\",\"options\":[\"a\",\"b\",\"c\",\"A\"],\"correct\":\"B\",\"marks\":1}";
            assertTrue(errorOf(api("POST", "/api/admin/exams/1/questions", dup, adminToken)).contains("different"), "duplicate options");
            String badKey = "{\"text\":\"Q?\",\"options\":[\"a\",\"b\",\"c\",\"d\"],\"correct\":\"Z\",\"marks\":1}";
            assertTrue(errorOf(api("POST", "/api/admin/exams/1/questions", badKey, adminToken)).contains("correct option"), "bad key");
            String zero = "{\"text\":\"Q?\",\"options\":[\"a\",\"b\",\"c\",\"d\"],\"correct\":\"B\",\"marks\":0}";
            assertTrue(errorOf(api("POST", "/api/admin/exams/1/questions", zero, adminToken)).contains("greater than zero"), "marks");
            String few = "{\"text\":\"Q?\",\"options\":[\"a\",\"b\"],\"correct\":\"B\",\"marks\":1}";
            assertEquals(400, api("POST", "/api/admin/exams/1/questions", few, adminToken).status, "missing options");
        });
        test("student answers are validated (bad option letters and ids)", () -> {
            assertEquals(400, api("PUT", "/api/student/attempts/1/answer", "{\"questionId\":1,\"option\":\"Z\"}", studentToken).status, "letter");
            assertEquals(400, api("PUT", "/api/student/attempts/1/answer", "{\"option\":\"A\"}", studentToken).status, "missing question");
            assertEquals(400, api("POST", "/api/student/attempts/1/submit", "{\"answers\":{\"x\":\"A\"}}", studentToken).status, "bad id");
            assertEquals(400, api("POST", "/api/student/attempts/1/submit", "{\"answers\":{\"1\":\"AB\"}}", studentToken).status, "bad letter");
            assertEquals(400, api("POST", "/api/student/attempts/1/submit", "{\"answers\":[1]}", studentToken).status, "wrong type");
        });
    }
}
