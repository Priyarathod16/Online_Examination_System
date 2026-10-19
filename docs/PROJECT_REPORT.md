# Online Examination System – Project Report

## 1. Overview

A Java Swing desktop application backed by MySQL (via JDBC) that lets administrators create timed multiple-choice exams
and lets students take them, with randomised questions and options, automatic evaluation, stored results, history and
leaderboards. The design follows the supplied project plan (*Online_Examination_System_Project_Report.pdf*) and its
non-functional requirements: server-side deadlines, transactional finalisation, hashed passwords, prepared statements,
role checks and no early exposure of the answer key.

### 1.1 Requirements traceability

| Requirement (from brief / PDF) | Where it is implemented |
|---|---|
| Admin login, role separation | `AuthService.login`, `UserSession`, `requireAdmin()` in every `AdminService` method |
| Create/edit/delete exams; duration, total questions, marks, pass % | `AdminService.saveExam/deleteExam`, `ExamFormDialog`; marks per question in `QuestionFormDialog` |
| Add/update/delete MCQs | `AdminService.saveQuestion/deleteQuestion`, `QuestionsTab` |
| View attempts, results, rankings | `AttemptsTab`, `LeaderboardPanel`, `ExamService.getLeaderboard` |
| Student registration/login | `AuthService.registerStudent/login`, `RegisterPanel`, `LoginPanel` |
| View exams, instructions | `StudentDashboardPanel`, `InstructionsPanel` |
| Timed exam, Next/Previous, review marks, resume | `ExamPanel`, `ExamService.startOrResume/saveAnswer/setMarkedForReview` |
| Manual or automatic submission | `ExamPanel.submitExam` (button / timer / server-rejected save), `ExamService.submit` |
| Auto evaluation, score/percentage/pass-fail, stored | `EvaluationService.evaluate`, `ExamService.finalizeLocked`, table `results` |
| History, leaderboard, permitted review | `ExamService.getHistory/getLeaderboard/getReview`, `exams.allow_review` |
| Fisher–Yates for questions and options | `ShuffleUtil`, `QuestionRandomizer` |
| HashMap question-id → answer | `ExamAttempt.answers`, `ResponseDAO.loadSelected` |
| Sorting for rankings | `RankingService` |
| Searching | `SearchUtil`, `StudentDAO.search`, filter boxes in the admin tabs |
| Queue (optional) | `WaitingListService`, `waiting_list` table |
| Deadline from stored times | `exam_attempts.deadline_at`, `AttemptDAO` (all comparisons use `NOW()`) |
| Transactions, idempotent submit | `ServiceSupport.inTransaction`, `ExamService.finalizeAttempt` |
| Credentials outside source control | `AppConfig`, `config/db.properties.example`, `.gitignore` |

## 2. Architecture

Layered, as recommended in the brief:

```
 ui (Swing panels/dialogs)
   │  user actions
 controller (Async / single-thread executor: keeps the EDT free, delivers results on the EDT)
   │
 service (rules, validation, role checks, transactions, evaluation, randomisation, ranking)
   │  Connection passed explicitly
 dao (PreparedStatement SQL only, no business rules)
   │  JDBC
 MySQL (constraints, unique keys, generated column, FKs)
```

* **model** – data classes. `AttemptQuestion` (student view) intentionally has no correct-answer property; `Question`
  (trusted view) does.
* **dao** – every method receives a `Connection`, so the *service* decides the transaction boundary. Student-facing
  queries never select `correct_option`.
* **service** – `ServiceSupport.read()` / `inTransaction()` open connections, set `READ COMMITTED`, commit or roll back,
  and convert `SQLException` into a user-safe `ServiceException` (details go to the log only).
* **controller** – `Async.run` (SwingWorker) for general calls; `ExamController` uses **one** single-thread executor
  for answer saves and the final submit so they reach the database in the order the student acted.
* **ui** – a single `MainFrame` swapping screens; shared look in `Theme`; toolbars use `WrapLayout`.

### 2.1 Exam workflow

1. `ExamService.startOrResume` (one transaction): lock the exam row → return the student's open attempt if one exists
   (finalising it first if its deadline passed) → otherwise check availability window, attempts left and seat limit →
   load the question pool (no answer key) → `QuestionRandomizer.assign` (Fisher–Yates) → insert the attempt (deadline =
   `NOW() + duration_minutes`) and the `attempt_questions` rows (question order and option order) → commit.
2. Each click → `ExamService.saveAnswer`: lock the attempt row `FOR SHARE` only if it is `IN_PROGRESS`, owned by the
   student and `deadline_at > NOW()`, then upsert the response. Otherwise `ExamClosedException`.
3. Submit / timer zero → `ExamService.submit` → `finalizeAttempt` (one transaction): `SELECT … FOR UPDATE` on the
   attempt; if already finalised return the stored result; otherwise (if not expired) store any unconfirmed answers,
   evaluate with the answer key, insert the result, set status `SUBMITTED` (or `TIMED_OUT` when the deadline had passed),
   commit.
4. Abandoned attempts: whenever a student opens the dashboard/history or an admin opens *Attempts*, expired in-progress
   attempts are finalised as `TIMED_OUT` with the answers saved before the deadline.

### 2.2 Why answers are keyed by question id and stored by original letter

Questions and options are shuffled per attempt. A response stores `(attempt_id, question_id, selected_option)` where
`selected_option` is the **original** letter (A–D) of the chosen text. `attempt_questions.option_order` (e.g. `CADB`)
records the order shown, so a resumed attempt looks identical and evaluation is independent of screen position.

### 2.3 Website version

`com.onlineexam.web` adds a second presentation layer next to the Swing UI. `WebServer` (JDK `HttpServer`) serves `web/`
and `/api`. `ApiHandler` is an adapter: it maps URLs to the same `AuthService`, `AdminService` and `ExamService` calls,
converts models to JSON (student-facing attempts use `AttemptQuestion`, so no answer key), and maps exceptions to HTTP
codes (`Validation→400`, `AccessDenied→403`, `ExamClosed/ExamFull→409`, database problems→503). Sessions are random tokens
in an `HttpOnly; SameSite=Strict` cookie mapped to the service-layer `UserSession` in `SessionStore`. The front end is plain
ES-module JavaScript (no libraries, no build step): `api.js` (fetch wrapper), `dom.js` (safe DOM builder using
`textContent`), `exam-logic.js` (countdown, serial save queue, palette state – unit tested), and `views/*.js`. The browser
timer is display-only; the server enforces `deadline_at`. The same DSA ideas appear on the client: a `Map` for
question id → answer, a `Set` for review marks and unsaved ids, and a serial promise queue so answers reach the server in order.

### 2.4 Standalone (single-file) website

`web/js/local/local-backend.js` implements the same JSON API in the browser (storage: `localStorage`; session:
`sessionStorage`; hashing: Web Crypto PBKDF2 with a pure-JS SHA-256 fallback). `api.js` routes calls to it when
`useLocalBackend()` has been called by `js/local/enable.js`. `scripts/build-standalone.mjs` wraps every ES module in a
function (browsers do not load modules from `file://`), inlines CSS and JS into `web/index.html`, and writes
`standalone/index.html`. All calls are serialised through a promise chain so two quick requests cannot overwrite each
other. Money-like values (marks) are evaluated in whole hundredths to avoid floating-point drift. The trade-off is
explicit: the browser user controls the data, so this mode is not tamper-proof (deadline and answer key are readable).

## 3. DSA used in the application

| Concept | Where | Why | Complexity |
|---|---|---|---|
| **Array** | `Question.options` (`String[4]`), `AttemptQuestion.options/letters`, permutation `int[]` | fixed option set, direct index | access O(1) |
| **ArrayList** | question lists everywhere (`ExamAttempt.questions`, pools, tables) | ordered, variable-size collections | get/set O(1), append amortised O(1) |
| **Fisher–Yates shuffle** | `ShuffleUtil.shuffle`, used by `QuestionRandomizer` for the question pool and for each question's options | unbiased random order and random subset | O(n) time, O(1) extra space (shuffle in place); picking k items O(k) |
| **HashMap** | `ExamAttempt.answers` (question id → letter), `ResponseDAO.loadResponses`, `EvaluationService.evaluate`, best-attempt-per-student in `RankingService` | constant-time lookup by stable id | average O(1) get/put |
| **HashSet** | marked-for-review ids, unsaved ids, duplicate-option check | membership tests | average O(1) |
| **Sorting** | `RankingService.rank` (`Collections.sort`, TimSort) | leaderboard: score ↓, percentage ↓, earlier submission, name, attempt id | O(n log n) |
| **Linear search** | `SearchUtil.filter/linearSearch` (admin filter boxes, "Search student") | substring search over loaded rows | O(n) |
| **Binary search** | `SearchUtil.binarySearchById` (restoring selection / combo selection in exam lists that are sorted by id) | id lookup in sorted list | O(log n) |
| **SQL search** | `StudentDAO.search` (`LIKE`), primary-key lookups | searching large tables | `LIKE '%x%'` O(n) scan; key lookups O(log n) |
| **Queue** | `WaitingListService` (`ArrayDeque.offer/poll`) with persistent order in `waiting_list` | first-come-first-served seat allocation | offer/poll O(1); position O(n) |

Fisher–Yates (as implemented):

```java
for (int i = list.size() - 1; i > 0; i--) {
    int j = random.nextInt(i + 1);   // 0 <= j <= i
    swap(list, i, j);
}
```
A unit test shuffles three items 60 000 times and checks that all six permutations appear with near-equal frequency.

## 4. Database design (MySQL 8.0.16+)

| Table | Purpose / key constraints |
|---|---|
| `students` | PK `student_id`; **UNIQUE** `email`; `password_hash` (never plain text) |
| `admins` | PK `admin_id`; **UNIQUE** `username` |
| `exams` | settings: duration, `total_questions`, `passing_percentage`, `negative_marks`, window `start_time/end_time`, `is_active`, shuffle flags, `allow_review`, `max_attempts`, `max_concurrent`; CHECKs for ranges and `end_time > start_time`; FK `created_by → admins` |
| `questions` | FK `exam_id → exams` (cascade); `option_a..d`, `correct_option` CHECK in A–D, `marks > 0` |
| `exam_attempts` | FK student, exam; `deadline_at`; `status` CHECK; **UNIQUE (student, exam, attempt_no)**; generated column `active_flag` + **UNIQUE (student, exam, active_flag)** → at most one open attempt per student and exam; CHECK linking `submitted_at` to `status` |
| `attempt_questions` *(added)* | PK (attempt, question); `display_order`, `option_order`; lets attempts be resumed exactly |
| `responses` | **UNIQUE (attempt_id, question_id)**; composite FK `(attempt_id, question_id) → attempt_questions` so only assigned questions can be answered; `marked_for_review` |
| `results` | **UNIQUE `attempt_id`** (one result per attempt); CHECKs `0 ≤ score ≤ max_score`, percentage 0–100, status PASS/FAIL |
| `waiting_list` *(added)* | UNIQUE (exam, student); order by `waiting_id` |

Indexes besides PK/UNIQUE: `idx_students_name`, `idx_exams_active_window`, `idx_exams_title`, `idx_questions_exam`,
`idx_attempts_exam_status`, `idx_attempts_status_deadline`, `idx_attempt_questions_question`, `idx_responses_question`,
`idx_waiting_student`. Foreign keys on history tables are `RESTRICT`: an exam with attempts, or a question already used,
cannot be deleted (the UI says so and suggests deactivating).

The full script with comments and sample data is `database/online_exam.sql`.

## 5. Important methods

| Method | Responsibility |
|---|---|
| `AuthService.login(role, id, password)` | validates input, throttles repeated failures, checks the PBKDF2 hash (also burns time for unknown users), returns a `UserSession` |
| `PasswordUtil.hash / verify` | salted PBKDF2-HMAC-SHA256, constant-time comparison |
| `ExamService.startOrResume` | transactional start/resume with seat, attempt and window checks |
| `QuestionRandomizer.assign` | chooses *k* of *n* questions and option orders using Fisher–Yates; clear error if fewer than *k* exist |
| `ExamService.saveAnswer` | deadline- and ownership-checked upsert of a response |
| `ExamService.finalizeAttempt / finalizeLocked` | locked, idempotent evaluation + result insert + status update |
| `EvaluationService.evaluate` | pure scoring: +marks per correct answer, optional −`negative_marks` per wrong answer, floor at 0, exact pass-threshold comparison |
| `RankingService.rank` | best attempt per student (HashMap), sort, assign ranks |
| `AdminService.saveExam / saveQuestion / setExamActive / deleteQuestion` | validation, consistency rules (an active exam must have enough questions) |
| `ServiceSupport.inTransaction` | connection, `READ COMMITTED`, commit/rollback, error translation |
| `ExamPanel` | countdown from the deadline, answer saving with honest status text, auto-submit with retry dialog |

Scoring rule: `score = Σ marks(correct) − negative_marks × (#wrong)`, never below 0;
`percentage = score / max × 100` (2 decimals); PASS when `score × 100 ≥ passing_percentage × max` (exact).

## 6. Security and reliability summary

* PBKDF2 hashes; no plain-text passwords; login failures are generic ("Invalid credentials"); 5 failures → 60 s lock.
* `PreparedStatement` for every statement with variable parts (the only concatenated SQL fragments are constants).
* Authorisation in the service layer, not only by hiding buttons; sessions cannot be forged by UI code.
* Answer key never sent to students before submission; review gated by finished status, ownership and exam setting.
* Deadline from the database clock; saves rejected after it; late submissions are scored on answers saved before it.
* Transactions with row locks for start and finalise; unique keys as a safety net.
* User-visible errors never contain SQL text; success is reported only after the database operation succeeded.
* Credentials in an untracked file or environment variables.

Residual risks (inherent to a desktop client): tampered clients, direct database credentials on each machine, no TLS
enforced by default (`useSSL=false` in the sample URL for local development – enable SSL for remote databases).

## 7. Testing

See `docs/TEST_CASES.md`. Automated: 31 unit tests (pure logic, role checks, failure handling) and a database
integration suite (login, exam creation, randomisation, resume, evaluation, timer expiry, duplicate and concurrent
submission, access control, DB failure).

## 8. Possible future work

Question categories and difficulty, CSV import, email notifications, a server-side web version (Spring Boot), negative
marking per question, analytics (hardest questions), downloadable result sheets.

## 9. Viva questions – short answers

1. *Why Java + MySQL?* Strong OOP/typing, Swing is built in, MySQL is a free relational database with transactions.
2. *JDBC?* The standard Java API; `DriverManager` + the Connector/J driver open connections, `PreparedStatement` runs SQL.
3. *Where are the data structures used?* See section 3.
4. *Fisher–Yates?* Walk from the end, swap each element with a random one at or before it; O(n), unbiased.
5. *Why key answers by question id?* Positions change when order is shuffled; ids are stable.
6. *PK/FK?* PK uniquely identifies a row; FK references another table's key to enforce relationships.
7. *PreparedStatement?* Parameters are sent separately from SQL text, preventing SQL injection and allowing reuse.
8. *Duplicate submissions?* Row lock + status check in a transaction, plus `UNIQUE(results.attempt_id)`.
9. *Deadline?* Stored `deadline_at`, compared with `NOW()` in SQL on every save/submit.
10. *Score/percentage?* Section 5.
11. *Transaction?* A group of statements that commit or roll back together; finalising must not leave a result without a closed attempt.
12. *Desktop limitations?* Client can be modified; use a server backend with HTTPS for strong integrity.
