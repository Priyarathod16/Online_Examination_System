# Online Examination System

A timed multiple-choice examination system, available in three forms:

1. **Standalone website – the easiest way: double-click `standalone/index.html`.** The complete website (login,
   registration, student and administrator areas, timed exams, results, leaderboard, review) in ONE file. No installation,
   no server, no database, no internet. Data is kept in your browser. See section 0.
2. **Website with a Java + MySQL backend** – the same pages served by a small Java server that stores everything in MySQL
   (shared by many users, server-enforced deadlines). See section 3A.
3. **Desktop application** – the original Java Swing client (Java, JDBC, MySQL).

Forms 2 and 3 share the same MySQL database and business logic.

Administrators create exams and questions; students register, take timed exams with randomised questions and options,
and receive automatically evaluated results, history and a leaderboard.

* Language level: Java 8 source (runs on JDK 8, 11, 17, 21 ...)
* Database: MySQL **8.0.16 or later**
* GUI: Java Swing (no external UI libraries)
* Only external dependency: MySQL Connector/J (JDBC driver)
* Passwords: salted PBKDF2-HMAC-SHA256 (JDK built-in, no extra library)

Documentation: [`docs/PROJECT_REPORT.md`](docs/PROJECT_REPORT.md) (architecture, DSA, SQL, key methods) and
[`docs/TEST_CASES.md`](docs/TEST_CASES.md) (test plan and results).

---

## 0. Standalone website (no setup)

**Open `standalone/index.html`** (identical copy: `standalone/OnlineExamSystem.html`) by double-clicking it. It works in
current versions of Chrome, Edge, Firefox and Safari, offline, from any folder, and you can copy that single file to
another computer or a USB stick.

Demo accounts are shown on the login page: administrator `admin` / `Admin@123`, students `alice@example.com` or
`bob@example.com` / `Student@123`. Two sample exams (Java: 25 questions, SQL: 12 questions) are created on first start.
**Reset sample data** on the login page restores them. Students can also register their own accounts, and further
administrators can be created from the login page (choose *Administrator* → *Create an administrator account*); that
screen asks for the administrator registration key, which is `EXAM-ADMIN-2026` in the standalone file (it is the constant
`ADMIN_REGISTRATION_KEY` in `web/js/local/local-backend.js` - change it, then rebuild, before sharing the file).

How it works: the same screens as the server version run on an in-browser implementation of the API
(`web/js/local/local-backend.js`) that follows the same rules as the Java services – shuffled questions and options
(Fisher–Yates), per-attempt deadline checked on every save and submit, idempotent submit, automatic evaluation with
negative marking, ranking, waiting list, review only when allowed, hashed passwords (PBKDF2), login throttling. Data is
stored in the browser's `localStorage` (per browser profile and per file location).

**What standalone mode is *not***
* Not secure against its own user: whoever uses the browser can open the developer tools, read the stored answer key or
  edit scores and deadlines. It is intended for demos, practice, classroom demonstrations or one-computer use – not for
  real, supervised or graded exams. Use form 2 for that.
* Not shared: every browser has its own data (no common leaderboard across computers). Clearing site data deletes it.
  Private/incognito windows may lose it when closed or may block storage (the login page then warns you).
* Changes to `web/` need a rebuild: `node scripts/build-standalone.mjs` (Node.js 18+, only for building).

---

## 1. Features

**Administrator** – secure login; create / edit / delete / activate / deactivate exams; add / edit / delete MCQs;
configure duration, questions per attempt, pass percentage, negative marking, attempts allowed, availability window,
seat limit, shuffling and whether answer review is allowed; view all attempts, results, class summary, a ranking per
exam and the list of students (with search).

**Student** – registration and login; list of available exams; instructions screen; timed exam with
Previous/Next, question palette, *mark for review*, *clear answer*, *save & exit* and resume; manual or automatic
submission; result screen (score, maximum, percentage, pass/fail); result history; leaderboard; answer review (only if the
exam allows it); optional waiting list when an exam has a seat limit.

**Result handling** – evaluated automatically in one database transaction; stored in MySQL; one result per attempt, guaranteed.

---

## 2. Project structure

```
OnlineExamSystem/
├── README.md
├── pom.xml                     Maven build (optional)
├── config/db.properties.example   copy to config/db.properties (git-ignored)
├── database/online_exam.sql    schema + sample data
├── lib/                        put the MySQL JDBC jar here (see lib/README.md)
├── standalone/                 index.html = the complete website in ONE file (double-click it; see section 0)
├── web/                        website sources: index.html, css/style.css, js/ (api, dom, exam logic, views, local/ = in-browser backend)
├── scripts/                    build / run / run-web / test scripts (.sh and .bat)
├── docs/                       project report, test cases, screenshots
└── src/
    ├── main/java/com/onlineexam/
    │   ├── app/         Main
    │   ├── model/       Exam, Question, AttemptQuestion, ExamAttempt, Result, ... (plain data classes)
    │   ├── dao/         DBConnection, StudentDAO, AdminDAO, ExamDAO, QuestionDAO, AttemptDAO, ResponseDAO, ResultDAO, WaitingListDAO
    │   ├── service/     AuthService, AdminService, ExamService, EvaluationService, QuestionRandomizer, RankingService, ...
    │   ├── web/         WebServer, ApiHandler (JSON API), StaticHandler, SessionStore, Json
    │   ├── controller/  AuthController, AdminController, StudentController, ExamController (UI <-> service, off the EDT)
    │   ├── util/        PasswordUtil, ShuffleUtil (Fisher-Yates), SearchUtil, ValidationUtil, AppConfig, ...
    │   ├── exception/   ServiceException and subclasses (messages are safe to show)
    │   └── ui/          Swing screens (login, dashboards, exam, result, dialogs)
    └── test/
        ├── java/com/onlineexam/service/   UnitTests, WebApiTests, DatabaseIntegrationTests, Harness
        └── js/                            front-end tests run with Node: web-tests, standalone-tests, bundle-test (+ fake-dom)
```

---

## 3. Installation and running (step by step)

### 3.1 Prerequisites

1. **JDK 8 or newer** (JDK 17 or 21 recommended). Check with `java -version` **and** `javac -version`
   (you need the JDK, not just the JRE).
2. **MySQL Server 8.0.16 or newer** running locally (or reachable over the network).
3. The **MySQL Connector/J** jar (JDBC driver).
4. *(Optional)* Maven 3.6+ if you prefer Maven. IntelliJ IDEA and Eclipse both work with or without Maven.

### 3.2 Create the database

From the project folder:

```bash
mysql -u root -p < database/online_exam.sql
```

On Windows you can also open the file in **MySQL Workbench** and run it. The script **drops and re-creates** the
`online_exam` database (so it can be re-run to reset the demo), creates all tables, and loads sample data.

Sample logins (loaded by the script):

| Role    | Login                | Password       |
|---------|----------------------|----------------|
| Admin   | `admin`              | `Admin@123`    |
| Student | `alice@example.com`  | `Student@123`  |
| Student | `bob@example.com`    | `Student@123`  |

> Change these passwords before using the system for anything real.

Optional but recommended: create a limited MySQL user for the app (the commented block at the top of `online_exam.sql`).

### 3.3 Add the JDBC driver (non-Maven setups)

Download `mysql-connector-j-8.4.0.jar` from Maven Central
(<https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/8.4.0/mysql-connector-j-8.4.0.jar>) and save it in `lib/`.
Maven users can skip this – `pom.xml` declares the dependency.

### 3.4 Configure the database connection

```bash
cp config/db.properties.example config/db.properties      # Windows: copy config\db.properties.example config\db.properties
```

Edit `config/db.properties` and set `db.url`, `db.user` and `db.password`. This file is listed in `.gitignore`, so your
password is never committed. (Alternatives: environment variables `EXAM_DB_URL`, `EXAM_DB_USER`, `EXAM_DB_PASSWORD`, or
`-Dexam.config=/path/to/file.properties`.)

### 3.5 Run

**Option A – scripts (plain JDK)**

```bash
./scripts/run.sh            # Linux / macOS
scripts\run.bat             # Windows
```

**Option B – Maven**

```bash
mvn compile exec:java       # run directly
mvn package                 # builds target/online-exam-system-1.0.0.jar (includes the driver)
java -jar target/online-exam-system-1.0.0.jar   # run from the project folder so config/db.properties is found
```

**Option C – IntelliJ IDEA**
1. *File → Open…* and select `pom.xml` → *Open as Project* (Maven downloads the driver), **or** open the folder, mark
   `src/main/java` as *Sources Root* and add `lib/mysql-connector-j-*.jar` under *Project Structure → Libraries*.
2. Run `com.onlineexam.app.Main`. Set the run configuration's **working directory to the project folder** so that
   `config/db.properties` is found.

**Option D – Eclipse**
1. *File → Import → Maven → Existing Maven Projects* (or create a Java project, add `src/main/java` and `src/test/java`
   as source folders and add the jar from `lib/` to the Build Path).
2. Run `com.onlineexam.app.Main` as a Java Application; in *Run Configurations → Arguments*, make sure the working
   directory is the project folder.

The login screen shows **"Database connection OK"** (green) when everything is configured correctly; otherwise it tells
you what is wrong (missing config file, missing driver, MySQL not reachable).

---

## 3A. The website version

The website lives in `web/` (`index.html`, `css/style.css`, `js/…`) and is served by `com.onlineexam.web.WebServer`, a
small Java HTTP server (JDK built-in `com.sun.net.httpserver`, no framework). It exposes a JSON API under `/api` that
simply calls the same services as the desktop app, so deadlines, transactions, role checks and answer-key protection are
identical. No Node.js, npm, CDN or internet connection is needed to run it.

**Start it** (after steps 3.1 – 3.4: JDK, MySQL database, driver jar in `lib/`, `config/db.properties`):

```bash
./scripts/run-web.sh                    # Linux / macOS     – Windows: scripts\run-web.bat
./scripts/run-web.sh --port 9000        # another port
./scripts/run-web.sh --host 0.0.0.0     # let other computers on your network connect (see security note)
```

then open **<http://localhost:8080/index.html>**. The console prints whether the database is reachable, and the login
page shows the same status. (Maven: `mvn compile exec:java -Dexec.mainClass=com.onlineexam.web.WebServer`.)
Run it from the project folder so `web/` and `config/db.properties` are found (`--web <folder>` overrides the first).

Use the same sample logins as the desktop app (`admin` / `Admin@123`, `alice@example.com` / `Student@123`).

**Pages** (single-page app with `#/…` routes, works on phones and desktops):

| Area | What you can do |
|---|---|
| Login / register | role switch (student / administrator), student registration, administrator registration (needs the registration key), validation |
| Student | exam cards, instructions with confirmation, timed exam (countdown, question palette, mark for review, clear answer, save & exit, resume, auto-submit), result page, history, review (if allowed), leaderboard, waiting list for full exams |
| Administrator | exams (create / edit / delete / activate, search), questions (add / edit / delete, search), attempts & results with summary, leaderboard, student search |

**How the website keeps exams honest**
* The countdown in the browser is display only. The deadline is stored in MySQL and checked by the server on every answer
  save and on submit; a save rejected as late makes the page submit automatically.
* A message "Answer saved" appears only after the server confirmed the save; otherwise the page says **NOT saved** and
  re-sends the answer with the submit request. Saves are queued so they reach the server in the order they were made.
* Questions sent to students contain no answer key; answers are stored by question id with the *original* option letter.
* Sessions: random 256-bit token in an `HttpOnly; SameSite=Strict` cookie, held in server memory (3 h idle timeout;
  restarting the server logs everyone out). Every state-changing request must send `X-Requested-With` and a JSON body
  (CSRF guard). Responses carry a strict Content-Security-Policy (no inline scripts), `X-Frame-Options: DENY`,
  `nosniff`; API responses are `no-store`. All user text is rendered with `textContent`, never as HTML.
* Path traversal is refused; only the `web/` folder is served (`config/` and `database/` are never reachable).

**Security note:** the server speaks plain HTTP. That is fine on `localhost`. If you expose it to other machines, put it
behind an HTTPS reverse proxy (nginx, Caddy, …) and start it with `--secure-cookie`. Do not publish it to the internet
with the sample passwords.

**API summary** (all JSON; errors look like `{"error": "...", "code": "validation|forbidden|closed|full|database|…"}`):

```
POST /api/login  /api/register  /api/logout      GET /api/me  /api/health
GET  /api/student/exams            POST /api/student/exams/{id}/start      POST /api/student/exams/{id}/waitlist
PUT  /api/student/attempts/{id}/answer | /mark    POST /api/student/attempts/{id}/submit
GET  /api/student/attempts/{id}/result | /review  GET  /api/student/history
GET  /api/leaderboard/exams  /api/leaderboard/{examId}
POST /api/register-admin   {username, password, confirm, adminKey}
GET/POST /api/admin/exams    PUT/DELETE /api/admin/exams/{id}    POST /api/admin/exams/{id}/active
GET/POST /api/admin/exams/{id}/questions         PUT/DELETE /api/admin/questions/{id}
GET  /api/admin/attempts?examId=   GET /api/admin/students?q=
```

---

## 4. Quick tour

1. Log in as **admin / Admin@123** → *Exams* tab: two sample exams exist. Use *New exam* to create another, then
   *Manage questions* to add questions. An exam can be activated only when its question bank has at least as many questions
   as "Questions per attempt".
2. Log in as **alice@example.com / Student@123** → *Available exams* → select an exam → *Start / resume* → read the
   instructions → tick the checkbox → *Start exam*.
3. Answer questions (each answer is saved immediately), use *Mark for review*, *Next/Previous* or the palette.
   *Save & exit* leaves the exam and lets you resume later; the timer keeps running. *Submit exam* finishes it.
4. After submission you see the result. *My results* shows the history; *Leaderboard* ranks students per exam.
   *Review answers* appears only for exams that allow it (the SQL sample exam does, the Java one does not).
5. To try the seat limit / waiting list: edit an exam and set "Max. students at once" to 1; start it with one student,
   then try with another student and choose to join the waiting list.

---

## 5. How the important requirements are met

| Requirement | Implementation |
|---|---|
| Deadline enforced, not just a countdown | `exam_attempts.deadline_at` is set from the **MySQL clock** (`NOW() + duration`). Every save runs `... AND deadline_at > NOW()` and submit compares against the same clock. The Swing timer is display only. Resume computes remaining time from the stored deadline. |
| No duplicate submission / result | Finalising locks the attempt row (`SELECT ... FOR UPDATE`) in one transaction; a repeat call sees `status <> IN_PROGRESS` and returns the stored result. `UNIQUE(results.attempt_id)` is the last line of defence. |
| Reliable saving | Each answer is its own transaction; the UI shows "Answer saved" only after the DB confirmed it, otherwise "NOT saved", and unsaved answers are re-sent on submit. Saves are queued in a single thread so older answers cannot overwrite newer ones. |
| Correct answers never exposed early | Student queries never select `correct_option`; students receive `AttemptQuestion` objects, which have no such field. The answer key is read only inside the evaluation transaction and for admins. Review is allowed only for finished attempts of exams with review enabled. |
| Role checks | Every service method calls `session.requireAdmin()` / `requireStudent()`. `UserSession` can only be created by `AuthService` after a password check. |
| SQL injection | All user-influenced values use `PreparedStatement` parameters. |
| Passwords | PBKDF2-HMAC-SHA256, 120 000 iterations, random 16-byte salt per password. 5 failed logins lock that account name for 60 seconds. |
| Credentials | Read from `config/db.properties` (git-ignored) or environment variables. |
| DB failures | Wrapped in `ServiceException` with a friendly message; raw SQL errors are only written to the log. |

DSA usage (details and complexities in the report): arrays for options, `ArrayList` for question lists, **Fisher–Yates**
shuffle for questions and options, `HashMap` for question-id → answer, `Collections.sort` for rankings, linear and binary
search, `ArrayDeque` queue for the waiting list.

---

## 6. Tests

```bash
./scripts/test.sh                 # all tests that need no database     – Windows: scripts\test.bat
./scripts/integration-test.sh     # end-to-end tests on your MySQL DB   – Windows: scripts\integration-test.bat
```

`test.sh` runs the Java unit tests (31), the website HTTP/API tests (22: starts the real server on a random port) and,
when Node.js is installed, the front-end tests: 38 for the website screens (real `web/js` code against a fake DOM and a mocked
`fetch`), 26 for the standalone backend (all rules plus a complete login → exam → result → admin journey through the real
screens) and 6 that run the generated single-file `standalone/index.html` exactly as shipped.

The integration tests need the database from step 3.2 and a valid `config/db.properties`; they create temporary data
named `ITEST…` and delete it afterwards. See [`docs/TEST_CASES.md`](docs/TEST_CASES.md).

---

## 7. Troubleshooting

| Message / symptom | Fix |
|---|---|
| "Database configuration not found" | Create `config/db.properties` (step 3.4); run from the project folder. |
| "The MySQL JDBC driver ... was not found" | Put the connector jar in `lib/` or use Maven. |
| "Cannot connect to MySQL" | Start MySQL; check host/port in `db.url`, user and password. |
| `Access denied for user` (in the log) | Wrong `db.user` / `db.password`. |
| Errors mentioning CHECK constraints or `FOR SHARE` | You need MySQL 8.0.16+. |
| Exam list is empty for a student | The exam must be *Active*, inside its availability window, and have enough questions. |
| Times look off by hours | All times use the MySQL server's time zone. |
| Locked out of an account | Wait 60 seconds (login throttling). |

---

## 8. Known limitations

* **Desktop client:** a determined user can tamper with a local client. The deadline and answer key are enforced in the
  database/service code, but for high-stakes exams a server-side application with HTTPS should be used (as the project
  brief notes).
* Single-answer MCQs only; no proctoring, essay questions or CSV import.
* Each client talks to MySQL directly with the credentials in its config file, so the database user should have only
  `SELECT, INSERT, UPDATE, DELETE` on `online_exam`.
* The waiting list is advisory: students press *Start* again when a seat frees up (there is no push notification).
* Dialog texts and UI are English only.
* The website's browser rendering and its calls against a live MySQL database were **not** exercised where the project was
  written (no real browser or database available there): the HTTP layer, the front-end logic and the standalone backend are
  tested with a fake browser; the visual design is not. Please click through the checklist in `docs/TEST_CASES.md` once.
* Standalone mode has the security limits described in section 0.
* Screenshots in `docs/screenshots` are layout previews rendered with sample data (no live database was available in
  the environment where the project was written).


## Creating administrator accounts

Administrators can see every student's results and change every exam, so the "Create an administrator account" screen
(login page → *Administrator* → *Create an administrator account*, in the desktop app, the website and the standalone
file) asks for an **administrator registration key** in addition to the new username and password. Without the key,
nobody can make themselves an administrator.

* Username: 3-80 characters (letters, digits, `.`, `_`, `-`; not case-sensitive). Password: the same rules as students.
* MySQL / desktop / website: set the key with `admin.registration.key` in `config/db.properties`, or the environment
  variable `EXAM_ADMIN_KEY`, or `-Dexam.admin.key=...`. If none is set, the demo default `EXAM-ADMIN-2026` applies -
  **change it** before anyone else can reach the application.
* Wrong keys are throttled (5 failures, then a one-minute lock), the key is compared in constant time, and it is never
  sent back to the browser.
* Standalone file: the key is the constant `ADMIN_REGISTRATION_KEY` in `web/js/local/local-backend.js`; run
  `scripts/build-standalone.sh` after changing it.
