// In-browser implementation of the website's JSON API, used by the single-file "standalone" build.
// It mirrors the Java services (same validation messages, deadline checks, idempotent submit, evaluation, ranking) so
// all screens work unchanged. Data lives in this browser's localStorage. It is meant for demos, practice and
// single-computer use: anyone with access to the browser can read or change the data (see README).

import { ApiError } from '../api.js';
import { SAMPLE } from './sample-data.js';
import { hashPassword, verifyPassword, randomInt } from './crypto-util.js';

const DATA_KEY = 'onlineExam.standalone.v1';
const SESSION_KEY = 'onlineExam.session.v1';
const LETTERS = 'ABCD';
// Key that must be typed to create an administrator account. Anyone who can read this file can see it: in the
// standalone build everything lives in the browser anyway (see README). Change it before sharing the file.
export const ADMIN_REGISTRATION_KEY = 'EXAM-ADMIN-2026';

let memoryDb = null;
let storageOk = true;
let clock = () => Date.now();
const failures = new Map();                 // login throttling: key -> {count, lockedUntil}
let chain = Promise.resolve();              // serialises all calls (so two quick requests never overwrite each other)

/** Tests can replace the clock. */
export function setClock(fn) { clock = fn; }

export function resetForTests() {
  memoryDb = null;
  storageOk = true;
  failures.clear();
}

// ------------------------------------------------------------------ storage

function store(kind) {
  try {
    return kind === 'session' ? globalThis.sessionStorage : globalThis.localStorage;
  } catch (e) {
    return null;
  }
}

function readData() {
  try {
    const s = store('data');
    const raw = s ? s.getItem(DATA_KEY) : null;
    return raw ? JSON.parse(raw) : null;
  } catch (e) {
    storageOk = false;
    return null;
  }
}

function commit(db) {
  memoryDb = db;
  try {
    const s = store('data');
    if (!s) throw new Error('no storage');
    s.setItem(DATA_KEY, JSON.stringify(db));
  } catch (e) {
    storageOk = false;            // keep working in memory; data is lost when the page is closed
  }
}

function readSession() {
  try {
    const s = store('session');
    const raw = s ? s.getItem(SESSION_KEY) : null;
    return raw ? JSON.parse(raw) : null;
  } catch (e) {
    return memorySession;
  }
}

let memorySession = null;
function writeSession(session) {
  memorySession = session;
  try {
    const s = store('session');
    if (!s) return;
    if (session) s.setItem(SESSION_KEY, JSON.stringify(session)); else s.removeItem(SESSION_KEY);
  } catch (e) { /* in-memory session only */ }
}

async function getDb() {
  const stored = readData();
  if (stored) return stored;
  if (memoryDb && !storageOk) return memoryDb;
  const fresh = await seed();
  commit(fresh);
  return fresh;
}

// -------------------------------------------------------------------- errors

const err = (status, code, message) => new ApiError(message, status, code);
const bad = (m) => err(400, 'validation', m);
const forbidden = (m) => err(403, 'forbidden', m);
const closed = (m) => err(409, 'closed', m);
const notFound = () => err(404, 'notfound', 'Unknown API endpoint.');

// --------------------------------------------------------------- validation
// (mirrors ValidationUtil / AdminService on the Java side, including the wording of the messages)

function requireText(value, field, max) {
  const v = typeof value === 'string' ? value.trim() : '';
  if (!v) throw bad(field + ' is required.');
  if (v.length > max) throw bad(field + ' must be at most ' + max + ' characters.');
  return v;
}

function optionalText(value, field, max) {
  const v = typeof value === 'string' ? value.trim() : '';
  if (v.length > max) throw bad(field + ' must be at most ' + max + ' characters.');
  return v || null;
}

const EMAIL = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;
function requireUsername(value) {
  const v = requireText(value, 'Username', 80);
  if (v.length < 3) throw bad('Username must be at least 3 characters long.');
  if (!/^[A-Za-z0-9._-]+$/.test(v)) throw bad('Username may contain only letters, digits, dots, underscores and hyphens.');
  return v;
}

function requireEmail(value) {
  const v = requireText(value, 'Email', 150).toLowerCase();
  if (!EMAIL.test(v)) throw bad('Please enter a valid email address.');
  return v;
}

function requirePassword(pw) {
  if (typeof pw !== 'string' || pw.length < 8) throw bad('Password must be at least 8 characters long.');
  if (pw.length > 100) throw bad('Password is too long (max 100 characters).');
  if (!/\p{L}/u.test(pw) || !/\d/.test(pw)) throw bad('Password must contain at least one letter and one digit.');
}

function isBlank(v) { return v === null || v === undefined || (typeof v === 'string' && v.trim() === ''); }

function toInt(v, label) {
  if (typeof v === 'number' && Number.isInteger(v)) return v;
  if (typeof v === 'string' && /^[+-]?\d+$/.test(v.trim())) return parseInt(v.trim(), 10);
  throw bad(label + ' must be a whole number.');
}

function reqInt(v, label) {
  if (v === null || v === undefined) throw bad(label + ' is required.');
  return toInt(v, label);
}

function optInt(v, label) { return isBlank(v) ? null : toInt(v, label); }

function toDecimal(v, label) {
  let n;
  if (typeof v === 'number') n = v;
  else if (typeof v === 'string' && /^[+-]?(\d+\.?\d*|\.\d+)$/.test(v.trim())) n = Number(v.trim());
  else throw bad(label + ' must be a number.');
  if (Math.abs(Math.round(n * 100) - n * 100) > 1e-7) throw bad(label + ' can have at most 2 decimal places.');
  return n;
}

function reqDecimal(v, label) {
  if (isBlank(v)) throw bad(label + ' is required.');
  return toDecimal(v, label);
}

function range(n, min, max, label) {
  if (n < min || n > max) throw bad(label + ' must be between ' + min + ' and ' + max + '.');
}

function parseDateTime(v, label) {
  if (isBlank(v)) return null;
  const s = String(v).trim();
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$/.test(s) || Number.isNaN(new Date(s).getTime())) throw bad(label + ' is not a valid date and time.');
  return s;
}

function bool(v, fallback) {
  if (v === null || v === undefined) return fallback;
  if (typeof v !== 'boolean') throw bad('A true/false field has an invalid value.');
  return v;
}

function localIso(ms) {
  if (ms === null || ms === undefined) return null;
  const d = new Date(ms);
  const p = (n) => String(n).padStart(2, '0');
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + 'T' + p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
}

// ------------------------------------------------------------------ sessions

function requireLogin() {
  const s = readSession();
  if (!s) throw err(401, 'unauthorized', 'Please log in to continue.');
  return s;
}
function requireStudent() {
  const s = requireLogin();
  if (s.role !== 'student') throw forbidden('Access denied: this action is only available to students.');
  return s;
}
function requireAdmin() {
  const s = requireLogin();
  if (s.role !== 'admin') throw forbidden('Access denied: administrator privileges are required.');
  return s;
}

// ------------------------------------------------------------ core algorithms

/** Fisher-Yates shuffle (in place): O(n) time, O(1) extra space, unbiased. */
export function shuffle(list) {
  for (let i = list.length - 1; i > 0; i--) {
    const j = randomInt(i + 1);            // 0 <= j <= i
    const tmp = list[i];
    list[i] = list[j];
    list[j] = tmp;
  }
  return list;
}

/** Chooses `count` questions and the option order of each (same behaviour as QuestionRandomizer.java). */
export function assignQuestions(pool, count, shuffleQuestions, shuffleOptions) {
  if (pool.length < count) {
    throw bad('This exam is not configured correctly: it needs ' + count + ' questions but only ' + pool.length
      + ' are available. Please contact the administrator.');
  }
  const work = pool.slice();
  if (shuffleQuestions) shuffle(work);
  return work.slice(0, count).map((q, i) => {
    const perm = [0, 1, 2, 3];
    if (shuffleOptions) shuffle(perm);
    return { qid: q.id, order: i + 1, optionOrder: perm.map((p) => LETTERS[p]).join('') };
  });
}

/** Scoring in whole hundredths so no floating-point error can change a result (same rules as EvaluationService.java). */
export function evaluate(assigned, answers, negativeMarks, passingPercentage) {
  let score = 0;
  let max = 0;
  const penalty = Math.round((negativeMarks || 0) * 100);
  for (const q of assigned) {
    const m = Math.round(q.marks * 100);
    max += m;
    const selected = answers[q.id];
    if (!selected) continue;
    if (selected === q.correct) score += m;
    else if (penalty > 0) score -= penalty;
  }
  if (score < 0) score = 0;
  const percentage = max === 0 ? 0 : Math.round((score * 10000) / max) / 100;
  const pass = max > 0 && score * 10000 >= Math.round(passingPercentage * 100) * max;
  return { score: score / 100, maxScore: max / 100, percentage, status: pass ? 'PASS' : 'FAIL' };
}

function rankingOrder(a, b) {
  if (a.score !== b.score) return b.score - a.score;
  if (a.percentage !== b.percentage) return b.percentage - a.percentage;
  if (a.submittedAt !== b.submittedAt) {
    if (a.submittedAt === null) return 1;
    if (b.submittedAt === null) return -1;
    return a.submittedAt - b.submittedAt;
  }
  const n = a.student.toLowerCase().localeCompare(b.student.toLowerCase());
  return n !== 0 ? n : a.attemptId - b.attemptId;
}

/** Best attempt per student (Map = O(1) lookups), then sort O(n log n), then rank 1..n. */
export function rankRows(rows) {
  const best = new Map();
  for (const r of rows) {
    const cur = best.get(r.studentId);
    if (!cur || rankingOrder(r, cur) < 0) best.set(r.studentId, r);
  }
  return [...best.values()].sort(rankingOrder).map((r, i) => ({ ...r, rank: i + 1 }));
}

/** First-come-first-served seats: 1-based position in the queue, or 0 when not queued. */
export function queuePosition(queueIds, studentId) {
  const i = queueIds.indexOf(studentId);
  return i < 0 ? 0 : i + 1;
}

export function isAdmitted(queueIds, studentId, freeSeats) {
  if (freeSeats <= 0) return false;
  let pos = queuePosition(queueIds, studentId);
  if (pos === 0) pos = queueIds.length + 1;
  return pos <= freeSeats;
}

// ------------------------------------------------------------------ data access

const byId = (list, id) => list.find((x) => x.id === id);
const questionsOf = (db, examId) => db.questions.filter((q) => q.examId === examId).sort((a, b) => a.id - b.id);
const isExpired = (a) => a.deadlineAt <= clock();
const isOpenNow = (e) => e.active && (!e.startTime || new Date(e.startTime).getTime() <= clock())
  && (!e.endTime || new Date(e.endTime).getTime() > clock());

function nextId(db, kind) {
  db.seq[kind] += 1;
  return db.seq[kind];
}

function finalizeAttempt(db, attempt, pending) {
  const expired = isExpired(attempt);
  if (!expired && pending) {
    for (const [qid, letter] of Object.entries(pending)) {
      if (!LETTERS.includes(letter) || letter.length !== 1) throw bad('Invalid answer option.');
      if (!attempt.questions.some((q) => String(q.qid) === String(qid))) throw bad('An answer refers to a question outside this attempt.');
      attempt.answers[qid] = letter;
    }
  }
  const exam = byId(db.exams, attempt.examId);
  const assigned = attempt.questions.map((aq) => byId(db.questions, aq.qid));
  const result = evaluate(assigned, attempt.answers, exam.negativeMarks, exam.passingPercentage);
  attempt.result = result;
  attempt.status = expired ? 'TIMED_OUT' : 'SUBMITTED';
  attempt.submittedAt = Math.min(clock(), attempt.deadlineAt);
  return result;
}

/** Finalises attempts whose deadline has passed (for one student, or everyone when studentId is null). */
function finalizeExpired(db, studentId) {
  let changed = false;
  for (const a of db.attempts) {
    if (a.status === 'IN_PROGRESS' && isExpired(a) && (studentId === null || a.studentId === studentId)) {
      finalizeAttempt(db, a, null);
      changed = true;
    }
  }
  if (changed) commit(db);
}

// -------------------------------------------------------------------- mappers

function examMap(e, db, forAdmin, studentId) {
  const m = {
    id: e.id, title: e.title, description: e.description, instructions: e.instructions, durationMinutes: e.durationMinutes,
    totalQuestions: e.totalQuestions, passingPercentage: e.passingPercentage, negativeMarks: e.negativeMarks, startTime: e.startTime,
    endTime: e.endTime, active: e.active, shuffleQuestions: e.shuffleQuestions, shuffleOptions: e.shuffleOptions,
    allowReview: e.allowReview, maxAttempts: e.maxAttempts, maxConcurrent: e.maxConcurrent,
  };
  if (forAdmin) {
    m.questionCount = questionsOf(db, e.id).length;
  } else {
    const mine = db.attempts.filter((a) => a.examId === e.id && a.studentId === studentId);
    const open = mine.find((a) => a.status === 'IN_PROGRESS');
    m.attemptsUsed = mine.length;
    m.activeAttemptId = open ? open.id : null;
  }
  return m;
}

function attemptMap(db, a) {
  const exam = byId(db.exams, a.examId);
  const questions = a.questions.map((aq) => {
    const q = byId(db.questions, aq.qid);
    return {
      id: q.id, text: q.text, marks: q.marks,
      options: [...aq.optionOrder].map((letter) => ({ letter, text: q.options[LETTERS.indexOf(letter)] })),
    };
  });
  return {
    attemptId: a.id, examTitle: exam.title, attemptNo: a.no, remainingSeconds: Math.max(0, Math.floor((a.deadlineAt - clock()) / 1000)),
    questions, answers: { ...a.answers }, marked: a.marked.map(Number),
  };
}

function resultMap(db, a) {
  const exam = byId(db.exams, a.examId);
  return {
    attemptId: a.id, examId: exam.id, examTitle: exam.title, score: a.result.score, maxScore: a.result.maxScore,
    percentage: a.result.percentage, status: a.result.status, timedOut: a.status === 'TIMED_OUT', reviewAllowed: exam.allowReview,
  };
}

// ---------------------------------------------------------------------- seeding

async function seed() {
  const adminHash = await hashPassword('Admin@123');
  const aliceHash = await hashPassword('Student@123');
  const bobHash = await hashPassword('Student@123');
  const created = clock();
  const db = {
    version: 1,
    seq: { student: 2, exam: 2, question: 0, attempt: 0, wait: 0 },
    admins: [{ id: 1, username: 'admin', hash: adminHash }],
    students: [
      { id: 1, name: 'Alice Johnson', email: 'alice@example.com', hash: aliceHash, createdAt: created },
      { id: 2, name: 'Bob Smith', email: 'bob@example.com', hash: bobHash, createdAt: created },
    ],
    exams: [
      { id: 1, title: 'Java Fundamentals Quiz', description: 'Core Java, OOP, collections and JDBC basics.',
        instructions: 'Answer every question. Each question has exactly one correct option. Unanswered questions score zero. Review answers are not shown after submission for this exam.',
        durationMinutes: 20, totalQuestions: 10, passingPercentage: 40, negativeMarks: 0, startTime: null, endTime: null, active: true,
        shuffleQuestions: true, shuffleOptions: true, allowReview: false, maxAttempts: 3, maxConcurrent: null },
      { id: 2, title: 'SQL Basics Quiz', description: 'Fundamental SQL queries, constraints and transactions.',
        instructions: 'Each wrong answer deducts 0.25 marks; unanswered questions score zero. You may review your answers after submitting.',
        durationMinutes: 15, totalQuestions: 10, passingPercentage: 50, negativeMarks: 0.25, startTime: null, endTime: null, active: true,
        shuffleQuestions: true, shuffleOptions: true, allowReview: true, maxAttempts: 2, maxConcurrent: null },
    ],
    questions: [], attempts: [], waiting: [],
  };
  for (const [examId, list] of [[1, SAMPLE.java], [2, SAMPLE.sql]]) {
    for (const q of list) db.questions.push({ id: nextId(db, 'question'), examId, text: q.text, options: q.options.slice(), correct: q.correct, marks: 1 });
  }
  // two finished sample attempts on exam 2 so the leaderboard and history are not empty
  const exam2 = db.questions.filter((q) => q.examId === 2).slice(0, 10);
  [[1, 8, new Date(2026, 8, 1, 10, 0).getTime()], [2, 5, new Date(2026, 8, 1, 11, 0).getTime()]].forEach(([studentId, correct, start]) => {
    const a = {
      id: nextId(db, 'attempt'), studentId, examId: 2, no: 1, startedAt: start, deadlineAt: start + 15 * 60000, submittedAt: start + 11 * 60000,
      status: 'SUBMITTED', questions: exam2.map((q, i) => ({ qid: q.id, order: i + 1, optionOrder: 'ABCD' })), answers: {}, marked: [], result: null,
    };
    exam2.slice(0, correct).forEach((q) => { a.answers[q.id] = q.correct; });
    a.result = evaluate(exam2, a.answers, 0.25, 50);
    db.attempts.push(a);
  });
  return db;
}

// ------------------------------------------------------------------- handlers

const DUMMY_HASH = 'pbkdf2$1000$AAAAAAAAAAAAAAAAAAAAAA==$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=';

async function login(db, body) {
  const role = body.role === 'admin' ? 'admin' : 'student';
  const id = typeof body.identifier === 'string' ? body.identifier.trim() : '';
  const password = body.password;
  if (!id || typeof password !== 'string' || !password) {
    throw bad('Please enter your ' + (role === 'admin' ? 'username' : 'email') + ' and password.');
  }
  const key = role + ':' + id.toLowerCase();
  const state = failures.get(key);
  if (state && state.lockedUntil > clock()) {
    throw err(429, 'locked', 'Too many failed attempts. Please wait ' + Math.ceil((state.lockedUntil - clock()) / 1000) + ' seconds and try again.');
  }
  const record = role === 'admin' ? db.admins.find((a) => a.username.toLowerCase() === id.toLowerCase())
    : db.students.find((s) => s.email === id.toLowerCase());
  let ok = false;
  if (record) ok = await verifyPassword(password, record.hash);
  else await verifyPassword(password, DUMMY_HASH);
  if (!ok) {
    let s = failures.get(key) || { count: 0, lockedUntil: 0 };
    if (s.lockedUntil !== 0 && s.lockedUntil <= clock()) s = { count: 0, lockedUntil: 0 };      // an earlier lock has expired
    s.count += 1;
    if (s.count >= 5) s.lockedUntil = clock() + 60000;
    failures.set(key, s);
    throw err(401, 'credentials', 'Invalid credentials. Please check your details and try again.');
  }
  failures.delete(key);
  const name = role === 'admin' ? record.username : record.name;
  writeSession({ role, id: record.id, name });
  return { role, name };
}

async function register(db, body) {
  const name = requireText(body.name, 'Name', 100);
  const email = requireEmail(body.email);
  requirePassword(body.password);
  if (body.password !== body.confirm) throw bad('The two passwords do not match.');
  if (db.students.some((s) => s.email === email)) throw bad('An account with this email already exists.');
  const hash = await hashPassword(body.password);
  db.students.push({ id: nextId(db, 'student'), name, email, hash, createdAt: clock() });
  commit(db);
  return { ok: true };
}

async function registerAdmin(db, body) {
  const username = requireUsername(body.username);
  requirePassword(body.password);
  if (body.password !== body.confirm) throw bad('The two passwords do not match.');
  const given = typeof body.adminKey === 'string' ? body.adminKey.trim() : '';
  if (!given) throw bad('Please enter the administrator registration key.');
  const key = 'admin-registration';
  const state = failures.get(key);
  if (state && state.lockedUntil > clock()) {
    throw err(429, 'locked', 'Too many failed attempts. Please wait ' + Math.ceil((state.lockedUntil - clock()) / 1000) + ' seconds and try again.');
  }
  if (given !== ADMIN_REGISTRATION_KEY) {
    let s = failures.get(key) || { count: 0, lockedUntil: 0 };
    if (s.lockedUntil !== 0 && s.lockedUntil <= clock()) s = { count: 0, lockedUntil: 0 };
    s.count += 1;
    if (s.count >= 5) s.lockedUntil = clock() + 60000;
    failures.set(key, s);
    throw err(403, 'forbidden', 'Invalid administrator registration key.');
  }
  failures.delete(key);
  if (db.admins.some((a) => a.username.toLowerCase() === username.toLowerCase())) {
    throw bad('An administrator with this username already exists.');
  }
  const hash = await hashPassword(body.password);
  db.admins.push({ id: db.admins.reduce((m, a) => Math.max(m, a.id), 0) + 1, username, hash });
  commit(db);
  return { ok: true };
}

function studentRoutes(db, method, seg, body) {
  const s = requireStudent();
  const part = seg[1];
  if (part === 'history' && method === 'GET') {
    finalizeExpired(db, s.id);
    return db.attempts.filter((a) => a.studentId === s.id && a.result)
      .sort((a, b) => b.submittedAt - a.submittedAt || b.id - a.id)
      .map((a) => ({
        attemptId: a.id, examId: a.examId, examTitle: byId(db.exams, a.examId).title, attemptNo: a.no, score: a.result.score,
        maxScore: a.result.maxScore, percentage: a.result.percentage, status: a.result.status, timedOut: a.status === 'TIMED_OUT',
        submittedAt: localIso(a.submittedAt), reviewAllowed: byId(db.exams, a.examId).allowReview,
      }));
  }
  if (part === 'exams') {
    if (seg.length === 2 && method === 'GET') {
      finalizeExpired(db, s.id);
      return db.exams.filter(isOpenNow).sort((a, b) => a.id - b.id).map((e) => examMap(e, db, false, s.id));
    }
    const examId = Number(seg[2]);
    if (seg[3] === 'start' && method === 'POST') return startOrResume(db, s, examId);
    if (seg[3] === 'waitlist' && method === 'POST') return joinWaitlist(db, s, examId);
    throw notFound();
  }
  if (part === 'attempts' && seg.length >= 4) {
    const attemptId = Number(seg[2]);
    const action = seg[3];
    if (action === 'answer' && method === 'PUT') return saveAnswer(db, s, attemptId, body);
    if (action === 'mark' && method === 'PUT') return saveMark(db, s, attemptId, body);
    if (action === 'submit' && method === 'POST') return submit(db, s, attemptId, body);
    if (action === 'result' && method === 'GET') return getResult(db, s, attemptId);
    if (action === 'review' && method === 'GET') return getReview(db, s, attemptId);
  }
  throw notFound();
}

function startOrResume(db, s, examId) {
  const exam = byId(db.exams, examId);
  if (!exam) throw bad('This exam no longer exists.');
  const existing = db.attempts.find((a) => a.studentId === s.id && a.examId === examId && a.status === 'IN_PROGRESS');
  if (existing) {
    if (isExpired(existing)) {
      finalizeAttempt(db, existing, null);
      commit(db);
      throw closed('Your previous attempt ran out of time and was submitted automatically. You can see the outcome under My Results.');
    }
    return attemptMap(db, existing);
  }
  if (!isOpenNow(exam)) throw closed('This exam is not open at the moment.');
  const used = db.attempts.filter((a) => a.studentId === s.id && a.examId === examId).length;
  if (used >= exam.maxAttempts) throw bad('You have already used all ' + exam.maxAttempts + ' attempt(s) for this exam.');
  if (exam.maxConcurrent !== null) {
    const active = db.attempts.filter((a) => a.examId === examId && a.status === 'IN_PROGRESS' && !isExpired(a)).length;
    const queue = db.waiting.filter((w) => w.examId === examId).sort((a, b) => a.seq - b.seq).map((w) => w.studentId);
    if (!isAdmitted(queue, s.id, exam.maxConcurrent - active)) {
      const pos = queuePosition(queue, s.id);
      throw err(409, 'full', pos > 0 ? 'All seats are taken. You are number ' + pos + ' in the waiting list.'
        : 'All seats are taken right now. You can join the waiting list.');
    }
  }
  const chosen = assignQuestions(questionsOf(db, examId), exam.totalQuestions, exam.shuffleQuestions, exam.shuffleOptions);
  const started = clock();
  const attempt = {
    id: nextId(db, 'attempt'), studentId: s.id, examId, no: used + 1, startedAt: started, deadlineAt: started + exam.durationMinutes * 60000,
    submittedAt: null, status: 'IN_PROGRESS', questions: chosen, answers: {}, marked: [], result: null,
  };
  db.attempts.push(attempt);
  db.waiting = db.waiting.filter((w) => !(w.examId === examId && w.studentId === s.id));
  commit(db);
  return attemptMap(db, attempt);
}

function joinWaitlist(db, s, examId) {
  const exam = byId(db.exams, examId);
  if (!exam || !isOpenNow(exam)) throw closed('This exam is not open at the moment.');
  if (exam.maxConcurrent === null) throw bad('This exam has no seat limit, so there is no waiting list.');
  if (!db.waiting.some((w) => w.examId === examId && w.studentId === s.id)) db.waiting.push({ examId, studentId: s.id, seq: nextId(db, 'wait') });
  commit(db);
  const queue = db.waiting.filter((w) => w.examId === examId).sort((a, b) => a.seq - b.seq).map((w) => w.studentId);
  return { position: queuePosition(queue, s.id) };
}

/** The attempt must be the student's own, still IN_PROGRESS and before its stored deadline. */
function openAttempt(db, s, attemptId) {
  const a = byId(db.attempts, attemptId);
  if (!a || a.studentId !== s.id || a.status !== 'IN_PROGRESS' || isExpired(a)) {
    throw closed('Time is up or this attempt is already closed, so the change was not saved.');
  }
  return a;
}

function saveAnswer(db, s, attemptId, body) {
  const option = body.option;
  if (option !== null && option !== undefined && (typeof option !== 'string' || option.length !== 1 || !LETTERS.includes(option.toUpperCase()))) {
    throw bad('Invalid answer option.');
  }
  const qid = reqInt(body.questionId, 'Question');
  const a = openAttempt(db, s, attemptId);
  if (!a.questions.some((q) => q.qid === qid)) throw bad('That question is not part of your attempt.');
  if (option === null || option === undefined) delete a.answers[qid]; else a.answers[qid] = option.toUpperCase();
  commit(db);
  return { ok: true };
}

function saveMark(db, s, attemptId, body) {
  const qid = reqInt(body.questionId, 'Question');
  const a = openAttempt(db, s, attemptId);
  if (!a.questions.some((q) => q.qid === qid)) throw bad('That question is not part of your attempt.');
  const marked = bool(body.marked, false);
  a.marked = a.marked.filter((id) => id !== qid);
  if (marked) a.marked.push(qid);
  commit(db);
  return { ok: true };
}

function submit(db, s, attemptId, body) {
  const a = byId(db.attempts, attemptId);
  if (!a || a.studentId !== s.id) throw forbidden('This attempt was not found.');
  if (a.status !== 'IN_PROGRESS') return resultMap(db, a);              // idempotent: same result again
  let pending = null;
  if (body.answers !== undefined && body.answers !== null) {
    if (typeof body.answers !== 'object' || Array.isArray(body.answers)) throw bad("'answers' must be an object.");
    pending = {};
    for (const [k, v] of Object.entries(body.answers)) {
      if (!/^\d+$/.test(k)) throw bad('Invalid question id in answers.');
      if (v === null) continue;
      if (typeof v !== 'string' || v.length !== 1) throw bad('Invalid answer option.');
      pending[k] = v.toUpperCase();
    }
  }
  finalizeAttempt(db, a, pending);
  commit(db);
  return resultMap(db, a);
}

function getResult(db, s, attemptId) {
  const a = byId(db.attempts, attemptId);
  if (!a || a.studentId !== s.id) throw forbidden('This attempt was not found.');
  if (!a.result) throw bad('The result for this attempt is not available yet.');
  return resultMap(db, a);
}

function getReview(db, s, attemptId) {
  const a = byId(db.attempts, attemptId);
  if (!a || a.studentId !== s.id) throw forbidden('This attempt was not found.');
  if (a.status === 'IN_PROGRESS') throw forbidden('Answers can be reviewed only after you submit.');
  if (!byId(db.exams, a.examId).allowReview) throw forbidden('Answer review is not enabled for this exam.');
  return a.questions.map((aq) => {
    const q = byId(db.questions, aq.qid);
    const selected = a.answers[aq.qid] || null;
    return {
      order: aq.order, text: q.text, options: [...aq.optionOrder].map((letter) => ({ letter, text: q.options[LETTERS.indexOf(letter)] })),
      selected, correct: q.correct, marks: q.marks, isCorrect: selected !== null && selected === q.correct,
    };
  });
}

function leaderboardRoutes(db, seg) {
  requireLogin();
  if (seg[1] === 'exams') return db.exams.filter((e) => e.active).sort((a, b) => a.id - b.id).map((e) => ({ id: e.id, title: e.title }));
  const examId = Number(seg[1]);
  if (!Number.isInteger(examId)) throw notFound();
  const rows = db.attempts.filter((a) => a.examId === examId && a.result).map((a) => ({
    studentId: a.studentId, student: byId(db.students, a.studentId).name, attemptId: a.id, score: a.result.score, maxScore: a.result.maxScore,
    percentage: a.result.percentage, status: a.result.status, submittedAt: a.submittedAt,
  }));
  return rankRows(rows).map((r) => ({
    rank: r.rank, student: r.student, score: r.score, maxScore: r.maxScore, percentage: r.percentage, status: r.status, submittedAt: localIso(r.submittedAt),
  }));
}

// ------------------------------------------------------------------------ admin

function readExam(body, existingId) {
  const title = body.title;
  const duration = reqInt(body.durationMinutes, 'Duration');
  const total = reqInt(body.totalQuestions, 'Questions per attempt');
  const pass = reqDecimal(body.passingPercentage, 'Passing percentage');
  const negative = isBlank(body.negativeMarks) ? 0 : toDecimal(body.negativeMarks, 'Negative marks');
  const startTime = parseDateTime(body.startTime, 'Available from');
  const endTime = parseDateTime(body.endTime, 'Available until');
  const attempts = reqInt(body.maxAttempts, 'Maximum attempts');
  const concurrent = optInt(body.maxConcurrent, 'Max. students at once');
  const exam = {
    id: existingId,
    title: requireText(title, 'Title', 150),
    description: optionalText(body.description, 'Description', 500),
    instructions: optionalText(body.instructions, 'Instructions', 10000),
    durationMinutes: duration, totalQuestions: total, passingPercentage: pass, negativeMarks: negative, startTime, endTime,
    active: bool(body.active, false), shuffleQuestions: bool(body.shuffleQuestions, true), shuffleOptions: bool(body.shuffleOptions, true),
    allowReview: bool(body.allowReview, false), maxAttempts: attempts, maxConcurrent: concurrent,
  };
  range(duration, 1, 600, 'Duration (minutes)');
  range(total, 1, 500, 'Total questions');
  range(pass, 0, 100, 'Passing percentage');
  range(negative, 0, 100, 'Negative marks');
  range(attempts, 1, 100, 'Maximum attempts');
  if (concurrent !== null) range(concurrent, 1, 100000, 'Maximum concurrent students');
  if (startTime && endTime && new Date(endTime).getTime() <= new Date(startTime).getTime()) throw bad('The end time must be after the start time.');
  return exam;
}

function readQuestion(body, examId, questionId) {
  const marks = reqDecimal(body.marks, 'Marks');
  const optionsIn = body.options;
  if (body.options !== undefined && body.options !== null && !Array.isArray(optionsIn)) throw bad("'options' must be a list.");
  const text = requireText(body.text, 'Question text', 5000);
  const options = [];
  const seen = new Set();
  for (let i = 0; i < 4; i++) {
    const o = requireText(Array.isArray(optionsIn) && typeof optionsIn[i] === 'string' ? optionsIn[i] : null, 'Option ' + LETTERS[i], 500);
    if (seen.has(o.toLowerCase())) throw bad('The four options must all be different.');
    seen.add(o.toLowerCase());
    options.push(o);
  }
  const correct = typeof body.correct === 'string' && body.correct.length === 1 ? body.correct.toUpperCase() : '?';
  if (!LETTERS.includes(correct)) throw bad('Select the correct option (A-D).');
  if (marks <= 0) throw bad('Marks must be greater than zero.');
  range(marks, 0.01, 1000, 'Marks');
  return { id: questionId, examId, text, options, correct, marks };
}

function adminRoutes(db, method, seg, body, query) {
  requireAdmin();
  const part = seg[1];
  if (part === 'exams') {
    if (seg.length === 2) {
      if (method === 'GET') return db.exams.slice().sort((a, b) => a.id - b.id).map((e) => examMap(e, db, true));
      if (method === 'POST') return saveExam(db, readExam(body, 0));
      throw err(405, 'method', 'Method not allowed.');
    }
    const examId = Number(seg[2]);
    if (!Number.isInteger(examId)) throw notFound();
    if (seg.length === 3) {
      if (method === 'PUT') return saveExam(db, readExam(body, examId));
      if (method === 'DELETE') return deleteExam(db, examId);
      throw err(405, 'method', 'Method not allowed.');
    }
    if (seg[3] === 'active' && method === 'POST') return setActive(db, examId, bool(body.active, false));
    if (seg[3] === 'questions') {
      if (method === 'GET') return questionsOf(db, examId).map(adminQuestionMap);
      if (method === 'POST') return saveQuestion(db, readQuestion(body, examId, 0));
    }
  }
  if (part === 'questions' && seg.length === 3) {
    const qid = Number(seg[2]);
    if (!Number.isInteger(qid)) throw notFound();
    if (method === 'PUT') return saveQuestion(db, readQuestion(body, reqInt(body.examId, 'Exam'), qid));
    if (method === 'DELETE') return deleteQuestion(db, qid);
  }
  if (part === 'attempts' && method === 'GET') {
    finalizeExpired(db, null);
    const examId = query.examId ? Number(query.examId) : null;
    return db.attempts.filter((a) => examId === null || a.examId === examId)
      .sort((a, b) => b.startedAt - a.startedAt || b.id - a.id).map((a) => {
        const st = byId(db.students, a.studentId);
        return {
          attemptId: a.id, student: st.name, email: st.email, examTitle: byId(db.exams, a.examId).title, attemptNo: a.no,
          startedAt: localIso(a.startedAt), submittedAt: localIso(a.submittedAt), status: a.status,
          score: a.result ? a.result.score : null, maxScore: a.result ? a.result.maxScore : null,
          percentage: a.result ? a.result.percentage : null, result: a.result ? a.result.status : null,
        };
      });
  }
  if (part === 'students' && method === 'GET') {
    const k = (query.q || '').trim().toLowerCase();
    return db.students.filter((s) => !k || s.name.toLowerCase().includes(k) || s.email.includes(k)).slice(0, 500).map((s) => ({
      id: s.id, name: s.name, email: s.email, registeredAt: localIso(s.createdAt), attempts: db.attempts.filter((a) => a.studentId === s.id).length,
    }));
  }
  throw notFound();
}

function adminQuestionMap(q) {
  return { id: q.id, examId: q.examId, text: q.text, options: q.options.slice(), correct: q.correct, marks: q.marks };
}

function saveExam(db, exam) {
  if (exam.id === 0) {
    if (exam.active) throw bad('Add questions first, then activate the exam.');
    exam.id = nextId(db, 'exam');
    db.exams.push(exam);
  } else {
    const idx = db.exams.findIndex((e) => e.id === exam.id);
    if (idx < 0) throw bad('This exam no longer exists.');
    if (exam.active && questionsOf(db, exam.id).length < exam.totalQuestions) {
      throw bad('An active exam needs at least ' + exam.totalQuestions + ' questions in its question bank. Add questions or deactivate the exam.');
    }
    db.exams[idx] = exam;
  }
  commit(db);
  return examMap(exam, db, true);
}

function deleteExam(db, examId) {
  if (!byId(db.exams, examId)) throw bad('This exam no longer exists.');
  if (db.attempts.some((a) => a.examId === examId)) {
    throw bad('This exam already has student attempts and cannot be deleted. Deactivate it instead to hide it from students.');
  }
  db.exams = db.exams.filter((e) => e.id !== examId);
  db.questions = db.questions.filter((q) => q.examId !== examId);
  db.waiting = db.waiting.filter((w) => w.examId !== examId);
  commit(db);
  return { ok: true };
}

function setActive(db, examId, active) {
  const exam = byId(db.exams, examId);
  if (!exam) throw bad('This exam no longer exists.');
  const count = questionsOf(db, examId).length;
  if (active && count < exam.totalQuestions) {
    throw bad('The exam needs ' + exam.totalQuestions + ' questions but its question bank only has ' + count + '. Add more questions first.');
  }
  exam.active = active;
  commit(db);
  return { ok: true };
}

function saveQuestion(db, q) {
  if (q.id === 0) {
    if (!byId(db.exams, q.examId)) throw bad('The exam for this question no longer exists.');
    q.id = nextId(db, 'question');
    db.questions.push(q);
  } else {
    const idx = db.questions.findIndex((x) => x.id === q.id);
    if (idx < 0 || db.questions[idx].examId !== q.examId) throw bad('This question no longer exists.');
    db.questions[idx] = q;
  }
  commit(db);
  return adminQuestionMap(q);
}

function deleteQuestion(db, qid) {
  const q = byId(db.questions, qid);
  if (!q) throw bad('This question no longer exists.');
  const exam = byId(db.exams, q.examId);
  if (exam && exam.active && questionsOf(db, exam.id).length - 1 < exam.totalQuestions) {
    throw bad('Deleting this question would leave the active exam with fewer than ' + exam.totalQuestions
      + ' questions. Deactivate the exam or add another question first.');
  }
  if (db.attempts.some((a) => a.questions.some((aq) => aq.qid === qid))) {
    throw bad('This question has already been used in student attempts and cannot be deleted. Edit it instead.');
  }
  db.questions = db.questions.filter((x) => x.id !== qid);
  commit(db);
  return { ok: true };
}

// ------------------------------------------------------------------------ entry

async function dispatch(method, rawPath, body) {
  const [path, queryString] = rawPath.split('?');
  const query = {};
  if (queryString) for (const pair of queryString.split('&')) {
    const i = pair.indexOf('=');
    query[decodeURIComponent(i < 0 ? pair : pair.slice(0, i))] = i < 0 ? '' : decodeURIComponent(pair.slice(i + 1).replace(/\+/g, ' '));
  }
  const seg = path.split('/').filter(Boolean).map(decodeURIComponent);
  const b = body && typeof body === 'object' && !Array.isArray(body) ? body : {};
  if (body !== undefined && body !== null && (typeof body !== 'object' || Array.isArray(body))) throw bad('The request body must be a JSON object.');
  const db = await getDb();
  switch (seg[0]) {
    case 'health':
      return {
        database: true, standalone: true,
        message: storageOk ? 'Standalone mode: everything runs in this browser and is saved in this browser only.'
          : 'Standalone mode: this browser blocks local storage, so data will be lost when you close the page.',
      };
    case 'login': return login(db, b);
    case 'register': return register(db, b);
    case 'register-admin': return registerAdmin(db, b);
    case 'logout': writeSession(null); return { ok: true };
    case 'me': return requireLogin() && { role: readSession().role, name: readSession().name };
    case 'student': return studentRoutes(db, method, seg, b);
    case 'admin': return adminRoutes(db, method, seg, b, query);
    case 'leaderboard': return leaderboardRoutes(db, seg);
    case 'standalone':
      if (seg[1] === 'reset' && method === 'POST') {
        try { store('data').removeItem(DATA_KEY); } catch (e) { /* ignore */ }
        memoryDb = null;
        failures.clear();
        writeSession(null);
        await getDb();
        return { ok: true };
      }
      throw notFound();
    default: throw notFound();
  }
}

/** The function registered with api.js: one call at a time, in order. */
export function localApi(method, path, body) {
  const run = chain.then(() => dispatch(method, path, body));
  chain = run.catch(() => undefined);
  return run;
}
