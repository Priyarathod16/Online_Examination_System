# Test cases and results

## How to run

```bash
./scripts/test.sh               # unit tests, no database          (Windows: scripts\test.bat)
./scripts/integration-test.sh   # needs MySQL + database/online_exam.sql + config/db.properties
```

## Status of this delivery (honest summary)

| Suite | Status |
|---|---|
| Unit tests (`UnitTests`, 31 tests) | **Executed – all 31 passed** (JDK 21, code compiled at Java-8 language level). |
| Website HTTP/API tests (`WebApiTests`, 22 tests) | **Executed – all passed.** Real server on a random port: static files, CSP/security headers, path traversal, 401/403/400/415/503 handling, role separation, cookie lifecycle, input validation. |
| Front-end tests (`src/test/js/web-tests.mjs`, 38 tests) | **Executed – all passed** (Node 22). The real `web/js` code against a fake DOM, mocked `fetch` and a manual clock: login, dashboards, exam flow (shuffled-option mapping, save ordering, NOT-saved handling, retry on failed submit, timer warning, auto-submit at zero, server-rejected late save), result/review, admin dialogs, router guards. Two deliberate code mutations were confirmed to be caught. |
| Standalone backend tests (`standalone-tests.mjs`, 26 tests) | **Executed – all passed.** Algorithms (Fisher–Yates uniformity, evaluation incl. negative marking and exact pass boundary, ranking, queue), hashing, accounts/sessions/lockout, every exam rule (no key sent to students, resume, validation, idempotent submit, timer expiry with a controllable clock, review gating, waiting list), all admin rules, persistence, blocked-storage fallback, plus one complete journey through the real screens. One genuine bug (login lockout counter) was found and fixed by these tests. |
| Generated single file (`bundle-test.mjs`, 6 steps) | **Executed – all passed.** Runs `standalone/index.html` exactly as shipped (no modules, no external resources, no network) and drives login, a wrong password, a full exam, evaluation and the admin attempts list. |
| Website in a real browser | **Not tested** (no browser available). Layout/CSS and real-browser behaviour need the manual checklist below. |
| Integration tests (`DatabaseIntegrationTests`) | **Written and compiled, but NOT executed**: no MySQL server was available where the project was written. Run them on your machine; if one fails, please check the SQL/MySQL version first (8.0.16+ required). |
| GUI | Panels were constructed and rendered off-screen with sample data (layout verified, see `docs/screenshots`). **Mouse/keyboard interaction against a live database was not exercised.** Please follow the manual checklist below. |
| SQL script | Written for MySQL 8.0.16+, **not executed** in the authoring environment. |

## Test cases from the project plan

| # | Test case (from the PDF) | Expected result | Automated test | Executed here |
|---|---|---|---|---|
| 1 | Valid student login | Student dashboard opens | Integration: *register / login*; manual step M1 | ✗ (needs DB) |
| 2 | Invalid password | Rejected without sensitive detail | Integration: *generic message, same text for unknown user*; Unit: *login with empty credentials* | partly (unit) |
| 3 | Exam has fewer questions than requested | Clear configuration error | Unit: *fewer questions than required*; Integration: *activation refused / start refused* | unit ✓ |
| 4 | Question randomisation | Unique ids per attempt, order differs across attempts | Unit: *unique ids*, *order differs*, *Fisher–Yates uniformity*, *option letters preserved*; Integration: *different order for second student* | unit ✓ |
| 5 | Correct answer | Configured marks awarded | Unit: *correct answers award the configured marks*; Integration: *6 correct → 6.00 / 10.00* | unit ✓ |
| 6 | Incorrect / unanswered | Follows scoring policy (0, or negative marks if configured) | Unit: *wrong and unanswered*, *negative marking*, *floor at zero*, *pass boundary* | unit ✓ |
| 7 | Manual submission | Finalised, result saved once | Integration: *score, max, percentage stored* | ✗ |
| 8 | Timer expires | Attempt finalised as TIMED_OUT using answers saved before the deadline | Integration: *saves rejected after deadline, submit → TIMED_OUT*, *abandoned attempt finalised on return* | ✗ |
| 9 | Repeated submit click | No duplicate result | Integration: *submit twice*, *8 simultaneous submits*, *DB refuses a 2nd result row* | ✗ |
| 10 | Database unavailable | Controlled error, nothing claimed as saved | Unit: *unreachable database…* (executed); Integration: *database going away* | unit ✓ |
| 11 | Student tries admin function | Access denied | Unit: *student is denied every admin service call*, *admin denied student actions*; Integration: *access control* | unit ✓ |
| 12 | Resume interrupted attempt | Saved responses and remaining deadline restored | Integration: *saved answers restored on resume* (same order, same option order, review marks, deadline not reset) | ✗ |

Additional automated cases: password hashing/salting/verification, email & password validation, exam/question
configuration validation, leaderboard ordering and best-attempt selection, waiting-list queue logic, binary/linear
search, review denied before submission, review denied to other students, exam with attempts cannot be deleted, login
throttling, duplicate e-mail registration.

## Manual website checklist (≈10 minutes)

Start `./scripts/run-web.sh`, open <http://localhost:8080/index.html> and repeat the desktop checklist below in the
browser, plus: log in on a phone-sized window (responsive layout), press the browser Back button during an exam (the exam
must still be resumable), open `http://localhost:8080/config/db.properties` and `/../pom.xml` (must NOT be served),
stop MySQL while answering (red "NOT saved" message), and check that closing the tab and reopening resumes the attempt.

## Manual GUI checklist for the desktop app (≈10 minutes)

| Step | Expected |
|---|---|
| M1 Start the app | Login screen shows "Database connection OK" |
| M2 Log in as `admin` / `Admin@123` | Admin dashboard with 5 tabs; Exams lists 2 sample exams |
| M3 New exam → leave title empty → Save | Message "Title is required." and the dialog stays open |
| M4 Create an exam, add 3 questions, try to activate with "Questions per attempt = 10" | Refused with a clear message |
| M5 Register a new student, log in | Student dashboard |
| M6 Open *Java Fundamentals Quiz* | Instructions; Start disabled until the checkbox is ticked |
| M7 Answer, press Next/Previous, mark for review, clear answer | Palette colours change; "Answer saved" appears |
| M8 *Save & exit*, re-open the exam | Same questions/options order, answers restored, time continues |
| M9 Stop MySQL, click an option | Red "NOT saved…" message (no false "saved"); restart MySQL and submit → works |
| M10 Let the timer run out (create an exam with duration 1 minute) | Auto-submission and result screen noting the time limit |
| M11 Submit; press Back and repeat on *SQL Basics Quiz* | Result; *Review answers* available only on SQL Basics |
| M12 Student tab *My results* / *Leaderboard* | History rows; ranked table |
| M13 Admin → Attempts & results | Rows with scores, summary line (average, pass rate) |
| M14 Five wrong logins | "Too many failed attempts…" for 60 s |
