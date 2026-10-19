-- =====================================================================
--  Online Examination System - MySQL 8.0.16+ schema and sample data
-- =====================================================================
--  Run with:   mysql -u root -p < database/online_exam.sql
--
--  WARNING: the next statement DROPS the database "online_exam" so that the
--  script can be re-run to reset the demo data. Remove it (and the sample
--  data section) if you already store data you want to keep.
--
--  Requirements: MySQL 8.0.16 or later (CHECK constraints are enforced from
--  that version; generated columns and FOR SHARE / FOR UPDATE OF are used).
--  All times are in the MySQL server's time zone.
-- =====================================================================

DROP DATABASE IF EXISTS online_exam;
CREATE DATABASE online_exam CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE online_exam;

-- ---------------------------------------------------------------------
-- Optional least-privilege account for the application (edit the password,
-- uncomment, and use it in config/db.properties instead of root):
--
-- CREATE USER 'exam_app'@'localhost' IDENTIFIED BY 'ChangeMe_123!';
-- GRANT SELECT, INSERT, UPDATE, DELETE ON online_exam.* TO 'exam_app'@'localhost';
-- ---------------------------------------------------------------------

-- ---------------------------------------------------------------------
-- students: registered examinees (password stored only as a salted hash)
-- ---------------------------------------------------------------------
CREATE TABLE students (
    student_id    INT          NOT NULL AUTO_INCREMENT,
    name          VARCHAR(100) NOT NULL,
    email         VARCHAR(150) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (student_id),
    UNIQUE KEY uq_students_email (email),
    KEY idx_students_name (name),
    CONSTRAINT chk_students_name CHECK (CHAR_LENGTH(TRIM(name)) > 0)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- admins: administrator accounts
-- ---------------------------------------------------------------------
CREATE TABLE admins (
    admin_id      INT          NOT NULL AUTO_INCREMENT,
    username      VARCHAR(80)  NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (admin_id),
    UNIQUE KEY uq_admins_username (username),
    CONSTRAINT chk_admins_username CHECK (CHAR_LENGTH(TRIM(username)) > 0)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- exams: exam definition and settings
--   max_concurrent  NULL = unlimited seats; otherwise a waiting list is used
--   negative_marks  marks deducted per wrong answer (0 = no negative marking)
-- ---------------------------------------------------------------------
CREATE TABLE exams (
    exam_id            INT           NOT NULL AUTO_INCREMENT,
    title              VARCHAR(150)  NOT NULL,
    description        VARCHAR(500)  NULL,
    instructions       TEXT          NULL,
    duration_minutes   INT           NOT NULL,
    total_questions    INT           NOT NULL,
    passing_percentage DECIMAL(5,2)  NOT NULL DEFAULT 40.00,
    negative_marks     DECIMAL(5,2)  NOT NULL DEFAULT 0.00,
    start_time         DATETIME      NULL,
    end_time           DATETIME      NULL,
    is_active          TINYINT(1)    NOT NULL DEFAULT 0,
    shuffle_questions  TINYINT(1)    NOT NULL DEFAULT 1,
    shuffle_options    TINYINT(1)    NOT NULL DEFAULT 1,
    allow_review       TINYINT(1)    NOT NULL DEFAULT 0,
    max_attempts       INT           NOT NULL DEFAULT 1,
    max_concurrent     INT           NULL,
    created_by         INT           NULL,
    created_at         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (exam_id),
    KEY idx_exams_active_window (is_active, start_time, end_time),
    KEY idx_exams_title (title),
    CONSTRAINT fk_exams_admin FOREIGN KEY (created_by) REFERENCES admins (admin_id) ON DELETE SET NULL,
    CONSTRAINT chk_exams_title    CHECK (CHAR_LENGTH(TRIM(title)) > 0),
    CONSTRAINT chk_exams_duration CHECK (duration_minutes BETWEEN 1 AND 600),
    CONSTRAINT chk_exams_total    CHECK (total_questions BETWEEN 1 AND 500),
    CONSTRAINT chk_exams_pass     CHECK (passing_percentage BETWEEN 0 AND 100),
    CONSTRAINT chk_exams_negative CHECK (negative_marks >= 0),
    CONSTRAINT chk_exams_window   CHECK (start_time IS NULL OR end_time IS NULL OR end_time > start_time),
    CONSTRAINT chk_exams_attempts CHECK (max_attempts >= 1),
    CONSTRAINT chk_exams_seats    CHECK (max_concurrent IS NULL OR max_concurrent >= 1)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- questions: single-answer MCQs (the answer key lives here only)
-- ---------------------------------------------------------------------
CREATE TABLE questions (
    question_id    INT           NOT NULL AUTO_INCREMENT,
    exam_id        INT           NOT NULL,
    question_text  TEXT          NOT NULL,
    option_a       VARCHAR(500)  NOT NULL,
    option_b       VARCHAR(500)  NOT NULL,
    option_c       VARCHAR(500)  NOT NULL,
    option_d       VARCHAR(500)  NOT NULL,
    correct_option CHAR(1)       NOT NULL,
    marks          DECIMAL(6,2)  NOT NULL DEFAULT 1.00,
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (question_id),
    KEY idx_questions_exam (exam_id),
    CONSTRAINT fk_questions_exam FOREIGN KEY (exam_id) REFERENCES exams (exam_id) ON DELETE CASCADE,
    CONSTRAINT chk_questions_correct CHECK (correct_option IN ('A','B','C','D')),
    CONSTRAINT chk_questions_marks   CHECK (marks > 0)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- exam_attempts: one row per attempt.
--   deadline_at is the OFFICIAL deadline (set from the database clock).
--   active_flag is a generated column that is 1 only while IN_PROGRESS; with the
--   unique key below it guarantees a student can never have two open attempts
--   of the same exam (MySQL allows many NULLs in a unique index).
-- ---------------------------------------------------------------------
CREATE TABLE exam_attempts (
    attempt_id   BIGINT      NOT NULL AUTO_INCREMENT,
    student_id   INT         NOT NULL,
    exam_id      INT         NOT NULL,
    attempt_no   INT         NOT NULL DEFAULT 1,
    started_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deadline_at  DATETIME    NOT NULL,
    submitted_at DATETIME    NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS',
    active_flag  TINYINT GENERATED ALWAYS AS (CASE WHEN status = 'IN_PROGRESS' THEN 1 ELSE NULL END) STORED,
    PRIMARY KEY (attempt_id),
    UNIQUE KEY uq_attempt_number (student_id, exam_id, attempt_no),
    UNIQUE KEY uq_one_open_attempt (student_id, exam_id, active_flag),
    KEY idx_attempts_exam_status (exam_id, status),
    KEY idx_attempts_status_deadline (status, deadline_at),
    CONSTRAINT fk_attempts_student FOREIGN KEY (student_id) REFERENCES students (student_id),
    CONSTRAINT fk_attempts_exam    FOREIGN KEY (exam_id)    REFERENCES exams (exam_id),
    CONSTRAINT chk_attempts_status   CHECK (status IN ('IN_PROGRESS','SUBMITTED','TIMED_OUT')),
    CONSTRAINT chk_attempts_deadline CHECK (deadline_at > started_at),
    CONSTRAINT chk_attempts_submitted CHECK (
        (status = 'IN_PROGRESS' AND submitted_at IS NULL) OR (status <> 'IN_PROGRESS' AND submitted_at IS NOT NULL))
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- attempt_questions: the questions assigned to an attempt, in display order,
--   plus the order in which the four options were shown (e.g. 'CADB').
--   Lets a resumed attempt look exactly as it did before.
-- ---------------------------------------------------------------------
CREATE TABLE attempt_questions (
    attempt_id    BIGINT  NOT NULL,
    question_id   INT     NOT NULL,
    display_order INT     NOT NULL,
    option_order  CHAR(4) NOT NULL DEFAULT 'ABCD',
    PRIMARY KEY (attempt_id, question_id),
    UNIQUE KEY uq_attempt_display_order (attempt_id, display_order),
    KEY idx_attempt_questions_question (question_id),
    CONSTRAINT fk_aq_attempt  FOREIGN KEY (attempt_id)  REFERENCES exam_attempts (attempt_id),
    CONSTRAINT fk_aq_question FOREIGN KEY (question_id) REFERENCES questions (question_id),
    CONSTRAINT chk_aq_order  CHECK (display_order >= 1),
    CONSTRAINT chk_aq_option_order CHECK (option_order REGEXP '^[ABCD]{4}$')
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- responses: selected option per (attempt, question). The option stored is the
--   ORIGINAL letter (A-D) of the option text, independent of shuffled display.
--   The composite foreign key to attempt_questions makes it impossible to
--   answer a question that was not assigned to the attempt.
-- ---------------------------------------------------------------------
CREATE TABLE responses (
    response_id       BIGINT     NOT NULL AUTO_INCREMENT,
    attempt_id        BIGINT     NOT NULL,
    question_id       INT        NOT NULL,
    selected_option   CHAR(1)    NULL,
    marked_for_review TINYINT(1) NOT NULL DEFAULT 0,
    updated_at        DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (response_id),
    UNIQUE KEY uq_response_attempt_question (attempt_id, question_id),
    KEY idx_responses_question (question_id),
    CONSTRAINT fk_resp_attempt  FOREIGN KEY (attempt_id)  REFERENCES exam_attempts (attempt_id),
    CONSTRAINT fk_resp_question FOREIGN KEY (question_id) REFERENCES questions (question_id),
    CONSTRAINT fk_resp_assigned FOREIGN KEY (attempt_id, question_id) REFERENCES attempt_questions (attempt_id, question_id),
    CONSTRAINT chk_resp_option CHECK (selected_option IS NULL OR selected_option IN ('A','B','C','D'))
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- results: exactly one final result per attempt (UNIQUE attempt_id)
-- ---------------------------------------------------------------------
CREATE TABLE results (
    result_id    BIGINT       NOT NULL AUTO_INCREMENT,
    attempt_id   BIGINT       NOT NULL,
    score        DECIMAL(8,2) NOT NULL,
    max_score    DECIMAL(8,2) NOT NULL,
    percentage   DECIMAL(5,2) NOT NULL,
    status       VARCHAR(10)  NOT NULL,
    evaluated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (result_id),
    UNIQUE KEY uq_results_attempt (attempt_id),
    CONSTRAINT fk_results_attempt FOREIGN KEY (attempt_id) REFERENCES exam_attempts (attempt_id),
    CONSTRAINT chk_results_scores  CHECK (score >= 0 AND max_score >= 0 AND score <= max_score),
    CONSTRAINT chk_results_percent CHECK (percentage BETWEEN 0 AND 100),
    CONSTRAINT chk_results_status  CHECK (status IN ('PASS','FAIL'))
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- waiting_list: optional FIFO queue for exams with a seat limit
-- ---------------------------------------------------------------------
CREATE TABLE waiting_list (
    waiting_id BIGINT   NOT NULL AUTO_INCREMENT,
    exam_id    INT      NOT NULL,
    student_id INT      NOT NULL,
    queued_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (waiting_id),
    UNIQUE KEY uq_waiting_exam_student (exam_id, student_id),
    KEY idx_waiting_student (student_id),
    CONSTRAINT fk_waiting_exam    FOREIGN KEY (exam_id)    REFERENCES exams (exam_id)       ON DELETE CASCADE,
    CONSTRAINT fk_waiting_student FOREIGN KEY (student_id) REFERENCES students (student_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- =====================================================================
--  SAMPLE DATA
--  Admin login:    username  admin               password  Admin@123
--  Student logins: alice@example.com / bob@example.com   password  Student@123
--  (Passwords are stored as salted PBKDF2-HMAC-SHA256 hashes.)
-- =====================================================================
INSERT INTO admins (admin_id, username, password_hash) VALUES
  (1, 'admin', 'pbkdf2_sha256$120000$9S8U45a7gbDOVWXgVi0yVA==$lGmM7TKI4UX2xjE1mRapd4cGq302ke8RbZcuWIfqGM8=');

INSERT INTO students (student_id, name, email, password_hash) VALUES
  (1, 'Alice Johnson', 'alice@example.com', 'pbkdf2_sha256$120000$IB4UD8HVKENsQsJw1VPf0Q==$joTKUkqjPZHMrqGcVFDV4ZcuU1xz6tv853ZBK2q925Q='),
  (2, 'Bob Smith',     'bob@example.com',   'pbkdf2_sha256$120000$IB4UD8HVKENsQsJw1VPf0Q==$joTKUkqjPZHMrqGcVFDV4ZcuU1xz6tv853ZBK2q925Q=');

INSERT INTO exams (exam_id, title, description, instructions, duration_minutes, total_questions, passing_percentage,
                   negative_marks, is_active, shuffle_questions, shuffle_options, allow_review, max_attempts, created_by) VALUES
  (1, 'Java Fundamentals Quiz',
      'Core Java, OOP, collections and JDBC basics.',
      'Answer every question. Each question has exactly one correct option. Unanswered questions score zero. Review answers are not shown after submission for this exam.',
      20, 10, 40.00, 0.00, 1, 1, 1, 0, 3, 1),
  (2, 'SQL Basics Quiz',
      'Fundamental SQL queries, constraints and transactions.',
      'Each wrong answer deducts 0.25 marks; unanswered questions score zero. You may review your answers after submitting.',
      15, 10, 50.00, 0.25, 1, 1, 1, 1, 2, 1);

-- Question bank for exam 1 (25 questions; each attempt draws 10 at random)
INSERT INTO questions (exam_id, question_text, option_a, option_b, option_c, option_d, correct_option, marks) VALUES
  (1, 'Which keyword is used to create a subclass in Java?', 'implements', 'extends', 'inherits', 'super', 'B', 1.00),
  (1, 'What is the default value of an int instance variable?', 'null', 'undefined', '0', '-1', 'C', 1.00),
  (1, 'Which collection class does NOT allow duplicate elements?', 'ArrayList', 'LinkedList', 'HashSet', 'Vector', 'C', 1.00),
  (1, 'Which method is the entry point of a Java application?', 'public static void main(String[] args)', 'public void start()', 'static void run()', 'public static int main()', 'A', 1.00),
  (1, 'Which of these is NOT a primitive type in Java?', 'int', 'boolean', 'String', 'char', 'C', 1.00),
  (1, 'What does JVM stand for?', 'Java Variable Machine', 'Java Virtual Machine', 'Java Verified Method', 'Joint Virtual Module', 'B', 1.00),
  (1, 'Which keyword prevents a method from being overridden by a subclass?', 'static', 'abstract', 'volatile', 'final', 'D', 1.00),
  (1, 'Which exception is thrown when an integer is divided by zero?', 'NullPointerException', 'ArithmeticException', 'NumberFormatException', 'IOException', 'B', 1.00),
  (1, 'What is the time complexity of reading an element by index from an ArrayList?', 'O(1)', 'O(n)', 'O(log n)', 'O(n log n)', 'A', 1.00),
  (1, 'Which interface should a class implement to define its natural sort order?', 'Runnable', 'Cloneable', 'Comparable', 'Serializable', 'C', 1.00),
  (1, 'Which statement about String in Java is true?', 'Strings are mutable', 'Strings are immutable', 'Strings are primitive types', 'Strings cannot be compared', 'B', 1.00),
  (1, 'Which block always executes after try/catch, whether or not an exception occurred?', 'catch', 'finally', 'throw', 'default', 'B', 1.00),
  (1, 'What is the result of the integer expression 5 / 2 in Java?', '2', '2.5', '3', '2.0', 'A', 1.00),
  (1, 'Which access modifier gives the widest visibility?', 'private', 'protected', 'package-private (no modifier)', 'public', 'D', 1.00),
  (1, 'Which collection stores key-value pairs using a hash table?', 'TreeSet', 'HashMap', 'ArrayDeque', 'LinkedList', 'B', 1.00),
  (1, 'Which keyword refers to the current object instance?', 'this', 'super', 'self', 'current', 'A', 1.00),
  (1, 'What does the static keyword on a method mean?', 'It belongs to the class rather than to an instance', 'It cannot return a value', 'It can only be called once', 'It is automatically synchronized', 'A', 1.00),
  (1, 'Which Swing class represents a top-level application window?', 'JPanel', 'JButton', 'JFrame', 'JLabel', 'C', 1.00),
  (1, 'Which JDBC interface runs a precompiled, parameterized SQL statement?', 'Statement', 'PreparedStatement', 'ResultSet', 'DriverManager', 'B', 1.00),
  (1, 'Which option best describes polymorphism?', 'One interface with many forms of behaviour', 'Hiding internal data', 'Creating objects from classes', 'Combining data and methods', 'A', 1.00),
  (1, 'What is encapsulation?', 'Inheriting from several classes', 'Converting one type to another', 'Binding data with methods and restricting direct access to it', 'Creating several methods with the same name', 'C', 1.00),
  (1, 'Which loop is guaranteed to run its body at least once?', 'for', 'while', 'for-each', 'do-while', 'D', 1.00),
  (1, 'Which HashMap method returns the value mapped to a key?', 'put', 'get', 'remove', 'clear', 'B', 1.00),
  (1, 'Which operator compares two primitive int values for equality?', '=', '===', 'equals', '==', 'D', 1.00),
  (1, 'What is the time complexity of the Fisher-Yates shuffle on n elements?', 'O(n)', 'O(n log n)', 'O(n^2)', 'O(log n)', 'A', 1.00);

-- Question bank for exam 2 (12 questions; each attempt draws 10 at random)
INSERT INTO questions (exam_id, question_text, option_a, option_b, option_c, option_d, correct_option, marks) VALUES
  (2, 'Which SQL statement retrieves rows from a table?', 'GET', 'SELECT', 'FETCH', 'OPEN', 'B', 1.00),
  (2, 'Which clause filters rows before they are grouped?', 'HAVING', 'GROUP BY', 'WHERE', 'ORDER BY', 'C', 1.00),
  (2, 'Which constraint uniquely identifies each row in a table?', 'FOREIGN KEY', 'PRIMARY KEY', 'CHECK', 'DEFAULT', 'B', 1.00),
  (2, 'Which JOIN returns only the rows that have matching values in both tables?', 'LEFT JOIN', 'RIGHT JOIN', 'CROSS JOIN', 'INNER JOIN', 'D', 1.00),
  (2, 'Which keyword removes duplicate rows from a result set?', 'UNIQUE', 'DISTINCT', 'DIFFERENT', 'REMOVE', 'B', 1.00),
  (2, 'Which statement changes existing data in a table?', 'MODIFY', 'CHANGE', 'UPDATE', 'ALTER ROW', 'C', 1.00),
  (2, 'What does a FOREIGN KEY enforce?', 'Referential integrity between tables', 'Faster sorting', 'Column encryption', 'Automatic numbering', 'A', 1.00),
  (2, 'Which aggregate function returns the number of rows?', 'SUM', 'COUNT', 'TOTAL', 'LENGTH', 'B', 1.00),
  (2, 'Which clause sorts the result set?', 'SORT BY', 'ORDER BY', 'GROUP BY', 'ARRANGE', 'B', 1.00),
  (2, 'Which command permanently saves the changes of a transaction?', 'ROLLBACK', 'SAVEPOINT', 'COMMIT', 'LOCK', 'C', 1.00),
  (2, 'Which statement removes a table together with all its data?', 'DELETE', 'TRUNCATE ROW', 'REMOVE TABLE', 'DROP TABLE', 'D', 1.00),
  (2, 'Why should applications use PreparedStatement for user input?', 'It helps prevent SQL injection', 'It makes tables smaller', 'It encrypts the database', 'It removes the need for indexes', 'A', 1.00);

-- Two finished sample attempts on exam 2 so the leaderboard and history are not empty.
INSERT INTO exam_attempts (attempt_id, student_id, exam_id, attempt_no, started_at, deadline_at, submitted_at, status) VALUES
  (1, 1, 2, 1, '2026-09-01 10:00:00', '2026-09-01 10:15:00', '2026-09-01 10:11:00', 'SUBMITTED'),
  (2, 2, 2, 1, '2026-09-01 11:00:00', '2026-09-01 11:15:00', '2026-09-01 11:14:00', 'SUBMITTED');

INSERT INTO attempt_questions (attempt_id, question_id, display_order, option_order)
SELECT a.attempt_id, t.question_id, t.rn, 'ABCD'
FROM (SELECT 1 AS attempt_id UNION ALL SELECT 2) a
JOIN (SELECT question_id, ROW_NUMBER() OVER (ORDER BY question_id) AS rn FROM questions WHERE exam_id = 2) t
  ON t.rn <= 10;

-- Alice answered 8 questions correctly (2 left blank); Bob answered 5 correctly (5 left blank).
INSERT INTO responses (attempt_id, question_id, selected_option)
SELECT aq.attempt_id, q.question_id, q.correct_option
FROM attempt_questions aq JOIN questions q ON q.question_id = aq.question_id
WHERE (aq.attempt_id = 1 AND aq.display_order <= 8) OR (aq.attempt_id = 2 AND aq.display_order <= 5);

INSERT INTO results (attempt_id, score, max_score, percentage, status) VALUES
  (1, 8.00, 10.00, 80.00, 'PASS'),
  (2, 5.00, 10.00, 50.00, 'PASS');

-- ---------------------------------------------------------------------
-- Handy queries (the application issues equivalent PreparedStatements)
-- ---------------------------------------------------------------------
-- Leaderboard for exam 2:
--   SELECT s.name, r.score, r.percentage FROM results r
--   JOIN exam_attempts a ON a.attempt_id = r.attempt_id JOIN students s ON s.student_id = a.student_id
--   WHERE a.exam_id = 2 ORDER BY r.score DESC, r.percentage DESC, a.submitted_at ASC;
-- A student's history (student 1):
--   SELECT e.title, r.score, r.max_score, r.percentage, r.status FROM results r
--   JOIN exam_attempts a ON a.attempt_id = r.attempt_id JOIN exams e ON e.exam_id = a.exam_id
--   WHERE a.student_id = 1 ORDER BY a.submitted_at DESC;
