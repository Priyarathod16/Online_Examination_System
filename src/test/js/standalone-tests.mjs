// Tests for the standalone (in-browser) backend and a complete user journey through the real UI code.
// Run with:  node src/test/js/standalone-tests.mjs
import assert from 'node:assert/strict';

const realSetTimeout = globalThis.setTimeout;
const { installDom, installTimers, flush, FakeElement } = await import('./fake-dom.mjs');

// ---- browser storage fakes
function makeStorage() {
  const m = new Map();
  return { getItem: (k) => (m.has(k) ? m.get(k) : null), setItem: (k, v) => { m.set(k, String(v)); }, removeItem: (k) => { m.delete(k); }, _m: m };
}
globalThis.localStorage = makeStorage();
globalThis.sessionStorage = makeStorage();

const W = '../../../web/js/';
const { ApiError, useLocalBackend, api } = await import(W + 'api.js');
const backend = await import(W + 'local/local-backend.js');
const crypto = await import(W + 'local/crypto-util.js');
crypto.setIterations(1000);                                   // keep tests fast
useLocalBackend(backend.localApi);
const { state } = await import(W + 'state.js');

let passed = 0;
let failed = 0;
let dom = installDom();
let timers = installTimers();
async function test(name, fn) {
  try {
    globalThis.localStorage = makeStorage();
    globalThis.sessionStorage = makeStorage();
    backend.resetForTests();
    backend.setClock(() => Date.now());
    crypto.setRandomSource(null);
    await fn();
    passed++;
    console.log('  PASS  ' + name);
  } catch (e) {
    failed++;
    console.log('  FAIL  ' + name + '\n        ' + (e && e.stack ? e.stack.split('\n').slice(0, 5).join('\n        ') : e));
  }
}
const section = (n) => console.log('\n== ' + n + ' ==');

const call = (m, p, b) => backend.localApi(m, p, b === undefined ? undefined : JSON.parse(JSON.stringify(b)));
async function fails(promise, status, code, includes) {
  try { await promise; } catch (e) {
    assert.ok(e instanceof ApiError, 'expected ApiError but got ' + e);
    assert.equal(e.status, status, e.message);
    if (code) assert.equal(e.code, code);
    if (includes) assert.ok(e.message.includes(includes), 'message was: ' + e.message);
    return e;
  }
  assert.fail('expected an error (' + status + ') but the call succeeded');
}
const loginAs = (role, id, pw) => call('POST', '/login', { role, identifier: id, password: pw });
const alice = () => loginAs('student', 'alice@example.com', 'Student@123');
const admin = () => loginAs('admin', 'admin', 'Admin@123');
const dbNow = () => JSON.parse(globalThis.localStorage.getItem('onlineExam.standalone.v1'));
/** answer key straight from storage (test-only) */
const keyOf = () => Object.fromEntries(dbNow().questions.map((q) => [q.id, q.correct]));
const wait = (ms) => new Promise((r) => realSetTimeout(r, ms));
async function until(cond, ms = 4000) {
  const end = Date.now() + ms;
  while (Date.now() < end) { if (cond()) return; await wait(5); await flush(); }
  assert.fail('condition not reached in time');
}

// ================================================================================== algorithms
section('Algorithms');

await test('Fisher-Yates yields permutations and is uniform', () => {
  const a = backend.shuffle([1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);
  assert.deepEqual([...a].sort((x, y) => x - y), [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);
  const counts = {};
  const trials = 60000;
  for (let i = 0; i < trials; i++) { const k = backend.shuffle([0, 1, 2]).join(''); counts[k] = (counts[k] || 0) + 1; }
  assert.equal(Object.keys(counts).length, 6);
  for (const v of Object.values(counts)) assert.ok(Math.abs(v - trials / 6) < (trials / 6) * 0.06, 'bias: ' + JSON.stringify(counts));
});

await test('assignQuestions: unique ids, exact count, option letters stay paired with their text, clear error when the bank is small', () => {
  const pool = Array.from({ length: 30 }, (_, i) => ({ id: i + 1 }));
  const chosen = backend.assignQuestions(pool, 10, true, true);
  assert.equal(chosen.length, 10);
  assert.equal(new Set(chosen.map((c) => c.qid)).size, 10);
  for (const c of chosen) assert.deepEqual([...c.optionOrder].sort(), ['A', 'B', 'C', 'D']);
  const fixed = backend.assignQuestions(pool, 5, false, false);
  assert.deepEqual(fixed.map((c) => c.qid), [1, 2, 3, 4, 5]);
  assert.ok(fixed.every((c) => c.optionOrder === 'ABCD'));
  assert.throws(() => backend.assignQuestions(pool.slice(0, 4), 10, true, true), (e) => e.message.includes('needs 10') && e.message.includes('only 4'));
});

await test('evaluate: marks, negative marking, floor at zero, exact pass boundary', () => {
  const qs = [{ id: 1, correct: 'A', marks: 1 }, { id: 2, correct: 'B', marks: 1 }, { id: 3, correct: 'C', marks: 2 }, { id: 4, correct: 'D', marks: 1 }];
  let r = backend.evaluate(qs, { 1: 'A', 3: 'C' }, 0, 40);
  assert.deepEqual([r.score, r.maxScore, r.percentage, r.status], [3, 5, 60, 'PASS']);
  r = backend.evaluate(qs, { 1: 'B', 2: 'A' }, 0, 40);
  assert.deepEqual([r.score, r.status], [0, 'FAIL']);
  r = backend.evaluate(qs, { 1: 'A', 2: 'C' }, 0.5, 40);               // +1 -0.5, unanswered free
  assert.equal(r.score, 0.5);
  r = backend.evaluate(qs, { 1: 'D', 2: 'A', 3: 'A', 4: 'A' }, 3, 40);
  assert.equal(r.score, 0, 'never below zero');
  assert.equal(backend.evaluate(qs, { 3: 'C' }, 0, 40).status, 'PASS', '2/5 = exactly 40%');
  assert.equal(backend.evaluate(qs, { 3: 'C' }, 0, 40.01).status, 'FAIL');
  assert.equal(backend.evaluate([], {}, 0, 40).percentage, 0);
  assert.equal(backend.evaluate([{ id: 1, correct: 'A', marks: 0.1 }, { id: 2, correct: 'A', marks: 0.2 }], { 1: 'A', 2: 'A' }, 0, 30).score, 0.3, 'no floating-point drift');
});

await test('ranking: best attempt per student, tie-breakers, queue helpers', () => {
  const rows = [
    { studentId: 1, student: 'Cara', attemptId: 1, score: 7, percentage: 70, submittedAt: 500 },
    { studentId: 2, student: 'Ben', attemptId: 2, score: 9, percentage: 90, submittedAt: 900 },
    { studentId: 3, student: 'Ann', attemptId: 3, score: 7, percentage: 70, submittedAt: 100 },
    { studentId: 4, student: 'Dan', attemptId: 4, score: 7, percentage: 75, submittedAt: 800 },
    { studentId: 3, student: 'Ann', attemptId: 5, score: 2, percentage: 20, submittedAt: 50 },
  ];
  const ranked = backend.rankRows(rows);
  assert.deepEqual(ranked.map((r) => r.student), ['Ben', 'Dan', 'Ann', 'Cara']);
  assert.deepEqual(ranked.map((r) => r.rank), [1, 2, 3, 4]);
  assert.equal(backend.queuePosition([10, 20, 30], 20), 2);
  assert.equal(backend.isAdmitted([10, 20, 30], 99, 3), false);
  assert.equal(backend.isAdmitted([10, 20, 30], 99, 4), true);
  assert.equal(backend.isAdmitted([10, 20, 30], 20, 2), true);
});

await test('password hashing: salted, verifiable, wrong password and garbage rejected', async () => {
  const a = await crypto.hashPassword('Secret123');
  const b = await crypto.hashPassword('Secret123');
  assert.notEqual(a, b);
  assert.ok(!a.includes('Secret123'));
  assert.equal(await crypto.verifyPassword('Secret123', a), true);
  assert.equal(await crypto.verifyPassword('secret123', a), false);
  assert.equal(await crypto.verifyPassword('x', 'plain-text'), false);
  assert.equal(await crypto.verifyPassword('x', null), false);
});

await test('fallback SHA-256 matches the known test vector', () => {
  const hex = (u) => [...u].map((b) => b.toString(16).padStart(2, '0')).join('');
  assert.equal(hex(crypto.sha256Fallback(new TextEncoder().encode('abc'))), 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
  assert.equal(hex(crypto.sha256Fallback(new Uint8Array(0))), 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855');
});

// ================================================================================== authentication
section('Standalone backend: accounts and sessions');

await test('sample data is created on first use and survives a reload', async () => {
  const h = await call('GET', '/health');
  assert.equal(h.standalone, true);
  await admin();
  assert.equal((await call('GET', '/admin/exams')).length, 2);
  backend.resetForTests();                                    // like reloading the page: memory gone, storage kept
  assert.equal((await call('GET', '/admin/exams')).length, 2);
  assert.ok(dbNow().students.every((s) => s.hash.startsWith('pbkdf2$') && !s.hash.includes('Student@123')), 'passwords are stored hashed');
});

await test('login: correct, wrong password, unknown user (same message), empty fields', async () => {
  assert.deepEqual(await admin(), { role: 'admin', name: 'admin' });
  assert.deepEqual(await alice(), { role: 'student', name: 'Alice Johnson' });
  const e1 = await fails(loginAs('student', 'alice@example.com', 'wrong-pass1'), 401, 'credentials', 'Invalid credentials');
  const e2 = await fails(loginAs('student', 'nobody@example.com', 'whatever123'), 401, 'credentials');
  assert.equal(e1.message, e2.message);
  await fails(loginAs('student', '', ''), 400, 'validation', 'email and password');
  await fails(loginAs('admin', 'alice@example.com', 'Student@123'), 401, 'credentials');
});

await test('five failed logins lock the account for a minute', async () => {
  let now = 1000000;
  backend.setClock(() => now);
  for (let i = 0; i < 5; i++) await fails(loginAs('student', 'bob@example.com', 'bad-password' + i), 401);
  await fails(loginAs('student', 'bob@example.com', 'Student@123'), 429, 'locked', 'Too many failed attempts');
  now += 61000;
  assert.equal((await loginAs('student', 'bob@example.com', 'Student@123')).role, 'student');
});

await test('registration validates input, rejects duplicates and allows login afterwards', async () => {
  const ok = { name: 'Zed', email: 'zed@example.com', password: 'Passw0rd1', confirm: 'Passw0rd1' };
  await fails(call('POST', '/register', { ...ok, name: ' ' }), 400, 'validation', 'Name is required');
  await fails(call('POST', '/register', { ...ok, email: 'nope' }), 400, 'validation', 'valid email');
  await fails(call('POST', '/register', { ...ok, password: 'short1', confirm: 'short1' }), 400, 'validation', 'at least 8');
  await fails(call('POST', '/register', { ...ok, password: 'onlyletters', confirm: 'onlyletters' }), 400, 'validation', 'letter and one digit');
  await fails(call('POST', '/register', { ...ok, confirm: 'Different1' }), 400, 'validation', 'do not match');
  await call('POST', '/register', ok);
  await fails(call('POST', '/register', { ...ok, email: 'ZED@example.com' }), 400, 'validation', 'already exists');
  assert.equal((await loginAs('student', 'zed@example.com', 'Passw0rd1')).name, 'Zed');
});

await test('administrator registration needs the key, validates input, rejects duplicates and allows login afterwards', async () => {
  const KEY = backend.ADMIN_REGISTRATION_KEY;
  const ok = { username: 'new.admin', password: 'Passw0rd1', confirm: 'Passw0rd1', adminKey: KEY };
  await fails(call('POST', '/register-admin', { ...ok, username: ' ' }), 400, 'validation', 'Username is required');
  await fails(call('POST', '/register-admin', { ...ok, username: 'ab' }), 400, 'validation', 'at least 3');
  await fails(call('POST', '/register-admin', { ...ok, username: 'has space' }), 400, 'validation', 'letters, digits');
  await fails(call('POST', '/register-admin', { ...ok, password: 'short1', confirm: 'short1' }), 400, 'validation', 'at least 8');
  await fails(call('POST', '/register-admin', { ...ok, confirm: 'Different1' }), 400, 'validation', 'do not match');
  await fails(call('POST', '/register-admin', { ...ok, adminKey: '' }), 400, 'validation', 'registration key');
  await fails(call('POST', '/register-admin', { ...ok, adminKey: 'wrong' }), 403, 'forbidden', 'Invalid administrator registration key');
  assert.equal(dbNow().admins.length, 1, 'nothing was created by the failed attempts');
  await call('POST', '/register-admin', ok);
  await fails(call('POST', '/register-admin', { ...ok, username: 'NEW.ADMIN' }), 400, 'validation', 'already exists');
  assert.deepEqual(await loginAs('admin', 'new.admin', 'Passw0rd1'), { role: 'admin', name: 'new.admin' });
  assert.equal((await call('GET', '/admin/exams')).length, 2, 'the new administrator can use the admin area');
  assert.equal(dbNow().admins.length, 2);
  assert.equal(dbNow().admins[1].id, 2);
  assert.ok(!JSON.stringify(dbNow().admins).includes('Passw0rd1'), 'only a hash is stored');
  await fails(loginAs('student', 'new.admin', 'Passw0rd1'), 401, 'credentials');
});

await test('wrong registration keys are throttled like wrong passwords', async () => {
  let now = 5000000;
  backend.setClock(() => now);
  const body = { username: 'locked.admin', password: 'Passw0rd1', confirm: 'Passw0rd1', adminKey: 'nope' };
  for (let i = 0; i < 5; i++) await fails(call('POST', '/register-admin', body), 403, 'forbidden');
  await fails(call('POST', '/register-admin', { ...body, adminKey: backend.ADMIN_REGISTRATION_KEY }), 429, 'locked', 'Too many failed attempts');
  now += 61000;
  await call('POST', '/register-admin', { ...body, adminKey: backend.ADMIN_REGISTRATION_KEY });
});

await test('session lifecycle and role separation', async () => {
  await fails(call('GET', '/me'), 401, 'unauthorized');
  await fails(call('GET', '/student/exams'), 401);
  await alice();
  assert.equal((await call('GET', '/me')).role, 'student');
  for (const [m, p] of [['GET', '/admin/exams'], ['POST', '/admin/exams'], ['GET', '/admin/attempts'], ['GET', '/admin/students'], ['DELETE', '/admin/exams/1'], ['POST', '/admin/exams/1/questions']]) {
    await fails(call(m, p, m === 'GET' || m === 'DELETE' ? undefined : {}), 403, 'forbidden');
  }
  await call('POST', '/logout', {});
  await fails(call('GET', '/me'), 401);
  await admin();
  for (const [m, p] of [['GET', '/student/exams'], ['POST', '/student/exams/1/start'], ['GET', '/student/history']]) {
    await fails(call(m, p, m === 'POST' ? {} : undefined), 403, 'forbidden');
  }
  await fails(call('GET', '/nothing'), 404);
});

// ================================================================================== exam flow
section('Standalone backend: taking an exam');

await test('start: unique questions, shuffled options, NO answer key anywhere in the data sent to the student', async () => {
  await alice();
  const list = await call('GET', '/student/exams');
  assert.deepEqual(list.map((e) => [e.id, e.attemptsUsed]), [[1, 0], [2, 1]]);
  const at = await call('POST', '/student/exams/1/start', {});
  assert.equal(at.questions.length, 10);
  assert.equal(new Set(at.questions.map((q) => q.id)).size, 10);
  assert.ok(at.remainingSeconds > 19 * 60 && at.remainingSeconds <= 1200);
  for (const q of at.questions) {
    assert.equal(q.options.length, 4);
    assert.deepEqual(q.options.map((o) => o.letter).sort(), ['A', 'B', 'C', 'D']);
  }
  const json = JSON.stringify(at);
  assert.ok(!json.includes('correct'), 'attempt payload must not contain the key');
  const key = keyOf();
  const q = at.questions[0];
  const original = dbNow().questions.find((x) => x.id === q.id);
  for (const o of q.options) assert.equal(o.text, original.options['ABCD'.indexOf(o.letter)], 'letter stays paired with its text');
  void key;
});

await test('resume returns the same attempt, order and saved answers; deadline is not reset', async () => {
  await alice();
  let now = 5000000;
  backend.setClock(() => now);
  const a = await call('POST', '/student/exams/1/start', {});
  const [q1, q2] = a.questions;
  await call('PUT', `/student/attempts/${a.attemptId}/answer`, { questionId: q1.id, option: q1.options[2].letter });
  await call('PUT', `/student/attempts/${a.attemptId}/answer`, { questionId: q2.id, option: 'A' });
  await call('PUT', `/student/attempts/${a.attemptId}/answer`, { questionId: q2.id, option: 'B' });
  await call('PUT', `/student/attempts/${a.attemptId}/mark`, { questionId: q2.id, marked: true });
  now += 60000;
  const b = await call('POST', '/student/exams/1/start', {});
  assert.equal(b.attemptId, a.attemptId);
  assert.deepEqual(b.questions.map((q) => q.id), a.questions.map((q) => q.id));
  assert.deepEqual(b.questions.map((q) => q.options.map((o) => o.letter).join('')), a.questions.map((q) => q.options.map((o) => o.letter).join('')));
  assert.equal(b.answers[q1.id], q1.options[2].letter);
  assert.equal(b.answers[q2.id], 'B', 'latest answer wins');
  assert.deepEqual(b.marked, [q2.id]);
  assert.equal(b.remainingSeconds, a.remainingSeconds - 60);
  assert.equal((await call('GET', '/student/exams'))[0].activeAttemptId, a.attemptId);
});

await test('answer validation: bad letters, questions outside the attempt, other students', async () => {
  await alice();
  const a = await call('POST', '/student/exams/1/start', {});
  const base = `/student/attempts/${a.attemptId}`;
  await fails(call('PUT', base + '/answer', { questionId: a.questions[0].id, option: 'Z' }), 400, 'validation', 'Invalid answer option');
  await fails(call('PUT', base + '/answer', { option: 'A' }), 400, 'validation', 'Question is required');
  const outside = dbNow().questions.find((q) => q.examId === 1 && !a.questions.some((x) => x.id === q.id)).id;
  await fails(call('PUT', base + '/answer', { questionId: outside, option: 'A' }), 400, 'validation', 'not part of your attempt');
  await call('POST', '/logout', {});
  await loginAs('student', 'bob@example.com', 'Student@123');
  await fails(call('PUT', base + '/answer', { questionId: a.questions[0].id, option: 'A' }), 409, 'closed');
  await fails(call('POST', base + '/submit', {}), 403, 'forbidden', 'not found');
  await fails(call('GET', base + '/result'), 403, 'forbidden');
});

await test('submit evaluates against the answer key and is idempotent (one result, same value every time)', async () => {
  await alice();
  const a = await call('POST', '/student/exams/1/start', {});
  const key = keyOf();
  a.questions.forEach((q, i) => { void i; });
  const base = `/student/attempts/${a.attemptId}`;
  for (let i = 0; i < 6; i++) await call('PUT', base + '/answer', { questionId: a.questions[i].id, option: key[a.questions[i].id] });          // 6 correct
  for (let i = 6; i < 8; i++) await call('PUT', base + '/answer', { questionId: a.questions[i].id, option: key[a.questions[i].id] === 'A' ? 'B' : 'A' }); // 2 wrong
  const r1 = await call('POST', base + '/submit', {});
  assert.deepEqual([r1.score, r1.maxScore, r1.percentage, r1.status, r1.timedOut], [6, 10, 60, 'PASS', false]);
  const r2 = await call('POST', base + '/submit', { answers: { [a.questions[9].id]: key[a.questions[9].id] } });
  assert.deepEqual(r2, r1, 'second submit returns the stored result and ignores new answers');
  assert.equal(dbNow().attempts.filter((x) => x.id === a.attemptId && x.result).length, 1);
  await fails(call('PUT', base + '/answer', { questionId: a.questions[0].id, option: 'A' }), 409, 'closed');
  assert.deepEqual(await call('GET', base + '/result'), r1);
  const hist = await call('GET', '/student/history');
  assert.equal(hist.length, 2);
  assert.equal(hist[0].attemptId, a.attemptId);
  await fails(call('GET', base + '/review'), 403, 'forbidden', 'not enabled');
  for (let n = 2; n <= 3; n++) {                                      // attempts 2 and 3 are allowed
    const next = await call('POST', '/student/exams/1/start', {});
    assert.notEqual(next.attemptId, a.attemptId);
    await call('POST', `/student/attempts/${next.attemptId}/submit`, {});
  }
  await fails(call('POST', '/student/exams/1/start', {}), 400, 'validation', 'used all 3');
});

await test('late saves are refused; late submit is TIMED_OUT and counts only earlier answers; abandoned attempts are finalised', async () => {
  await alice();
  let now = 10000000;
  backend.setClock(() => now);
  const a = await call('POST', '/student/exams/1/start', {});
  const key = keyOf();
  const [q1, q2] = a.questions;
  await call('PUT', `/student/attempts/${a.attemptId}/answer`, { questionId: q1.id, option: key[q1.id] });
  now += 21 * 60000;                                                // past the 20-minute deadline
  await fails(call('PUT', `/student/attempts/${a.attemptId}/answer`, { questionId: q2.id, option: key[q2.id] }), 409, 'closed', 'Time is up');
  const r = await call('POST', `/student/attempts/${a.attemptId}/submit`, { answers: { [q2.id]: key[q2.id] } });
  assert.deepEqual([r.score, r.timedOut], [1, true]);
  assert.equal(dbNow().attempts.find((x) => x.id === a.attemptId).submittedAt, dbNow().attempts.find((x) => x.id === a.attemptId).deadlineAt);
  // abandoned attempt
  const b = await call('POST', '/student/exams/1/start', {});
  now += 25 * 60000;
  await call('GET', '/student/exams');                              // dashboard load finalises it
  assert.equal(dbNow().attempts.find((x) => x.id === b.attemptId).status, 'TIMED_OUT');
  // resuming an expired attempt reports it instead of reopening it
  const c = await call('POST', '/student/exams/1/start', {});
  now += 25 * 60000;
  await fails(call('POST', '/student/exams/1/start', {}), 409, 'closed', 'ran out of time');
  assert.equal(dbNow().attempts.find((x) => x.id === c.attemptId).status, 'TIMED_OUT');
});

await test('review: available after submitting when the exam allows it, with correct/selected data', async () => {
  await alice();
  const a = await call('POST', '/student/exams/2/start', {});
  await fails(call('GET', `/student/attempts/${a.attemptId}/review`), 403, 'forbidden', 'only after you submit');
  const key = keyOf();
  await call('PUT', `/student/attempts/${a.attemptId}/answer`, { questionId: a.questions[0].id, option: key[a.questions[0].id] });
  const r = await call('POST', `/student/attempts/${a.attemptId}/submit`, {});
  assert.equal(r.reviewAllowed, true);
  assert.equal(r.score, 1);
  const rev = await call('GET', `/student/attempts/${a.attemptId}/review`);
  assert.equal(rev.length, 10);
  assert.equal(rev[0].isCorrect, true);
  assert.equal(rev[1].selected, null);
  assert.equal(rev[0].correct, key[a.questions[0].id]);
  await call('POST', '/logout', {});
  await loginAs('student', 'bob@example.com', 'Student@123');
  await fails(call('GET', `/student/attempts/${a.attemptId}/review`), 403, 'forbidden');
});

await test('leaderboard: sample results ranked; attempts and history for the sample students', async () => {
  await alice();
  const lb = await call('GET', '/leaderboard/2');
  assert.deepEqual(lb.map((r) => [r.rank, r.student, r.score, r.percentage, r.status]), [[1, 'Alice Johnson', 8, 80, 'PASS'], [2, 'Bob Smith', 5, 50, 'PASS']]);
  assert.deepEqual(await call('GET', '/leaderboard/1'), []);
  assert.deepEqual((await call('GET', '/leaderboard/exams')).map((e) => e.id), [1, 2]);
  const h = await call('GET', '/student/history');
  assert.deepEqual([h[0].examTitle, h[0].score, h[0].reviewAllowed], ['SQL Basics Quiz', 8, true]);
});

await test('seat limit and waiting list work first-come first-served', async () => {
  await admin();
  const ex = (await call('GET', '/admin/exams'))[0];
  await call('PUT', '/admin/exams/1', { ...ex, maxConcurrent: 1 });
  await call('POST', '/logout', {});
  await alice();
  const a = await call('POST', '/student/exams/1/start', {});
  await call('POST', '/logout', {});
  await loginAs('student', 'bob@example.com', 'Student@123');
  await fails(call('POST', '/student/exams/1/start', {}), 409, 'full', 'join the waiting list');
  assert.deepEqual(await call('POST', '/student/exams/1/waitlist', {}), { position: 1 });
  assert.deepEqual(await call('POST', '/student/exams/1/waitlist', {}), { position: 1 }, 'joining twice keeps one place');
  await fails(call('POST', '/student/exams/1/start', {}), 409, 'full', 'number 1');
  await call('POST', '/logout', {});
  await alice();
  await call('POST', `/student/attempts/${a.attemptId}/submit`, {});
  await call('POST', '/logout', {});
  await loginAs('student', 'bob@example.com', 'Student@123');
  assert.equal((await call('POST', '/student/exams/1/start', {})).questions.length, 10);
  assert.equal(dbNow().waiting.length, 0);
});

// ================================================================================== administration
section('Standalone backend: administration');

const examBody = (o = {}) => ({ title: 'T', durationMinutes: 30, totalQuestions: 2, passingPercentage: 40, maxAttempts: 1, ...o });
const qBody = (o = {}) => ({ text: 'Q?', options: ['a', 'b', 'c', 'd'], correct: 'B', marks: 1, ...o });

await test('exam validation messages', async () => {
  await admin();
  await fails(call('POST', '/admin/exams', {}), 400, 'validation', 'Duration is required');
  await fails(call('POST', '/admin/exams', examBody({ durationMinutes: 0 })), 400, 'validation', 'between 1 and 600');
  await fails(call('POST', '/admin/exams', examBody({ durationMinutes: 'abc' })), 400, 'validation', 'whole number');
  await fails(call('POST', '/admin/exams', examBody({ durationMinutes: 1.5 })), 400, 'validation', 'whole number');
  await fails(call('POST', '/admin/exams', examBody({ title: '  ' })), 400, 'validation', 'Title is required');
  await fails(call('POST', '/admin/exams', examBody({ passingPercentage: 101 })), 400, 'validation', 'between 0 and 100');
  await fails(call('POST', '/admin/exams', examBody({ startTime: 'nonsense' })), 400, 'validation', 'valid date');
  await fails(call('POST', '/admin/exams', examBody({ startTime: '2026-05-02T10:00', endTime: '2026-05-01T10:00' })), 400, 'validation', 'after the start');
  await fails(call('POST', '/admin/exams', examBody({ active: true })), 400, 'validation', 'Add questions first');
  await fails(call('PUT', '/admin/exams/99', examBody()), 400, 'validation', 'no longer exists');
});

await test('question validation messages', async () => {
  await admin();
  const url = '/admin/exams/1/questions';
  await fails(call('POST', url, qBody({ options: ['a', 'b', 'c', 'A'] })), 400, 'validation', 'different');
  await fails(call('POST', url, qBody({ options: ['a', 'b', 'c', ''] })), 400, 'validation', 'Option D is required');
  await fails(call('POST', url, qBody({ correct: 'Z' })), 400, 'validation', 'correct option');
  await fails(call('POST', url, qBody({ marks: 0 })), 400, 'validation', 'greater than zero');
  await fails(call('POST', url, qBody({ marks: '1.234' })), 400, 'validation', '2 decimal');
  await fails(call('POST', url, qBody({ options: ['a', 'b'] })), 400, 'validation');
  await fails(call('POST', '/admin/exams/77/questions', qBody()), 400, 'validation', 'no longer exists');
});

await test('create exam, add questions, activation rule, edit, deactivate, delete rules', async () => {
  await admin();
  const e = await call('POST', '/admin/exams', examBody({ title: 'My exam', totalQuestions: 3 }));
  assert.deepEqual([e.active, e.questionCount], [false, 0]);
  const q1 = await call('POST', `/admin/exams/${e.id}/questions`, qBody({ text: 'One' }));
  await call('POST', `/admin/exams/${e.id}/questions`, qBody({ text: 'Two' }));
  await fails(call('POST', `/admin/exams/${e.id}/active`, { active: true }), 400, 'validation', 'needs 3 questions');
  await call('POST', `/admin/exams/${e.id}/questions`, qBody({ text: 'Three' }));
  await call('POST', `/admin/exams/${e.id}/active`, { active: true });
  await fails(call('DELETE', `/admin/questions/${q1.id}`), 400, 'validation', 'fewer than 3');
  await call('PUT', `/admin/questions/${q1.id}`, { ...qBody({ text: 'One (edited)', correct: 'C' }), examId: e.id });
  assert.equal((await call('GET', `/admin/exams/${e.id}/questions`))[0].text, 'One (edited)');
  await call('POST', `/admin/exams/${e.id}/active`, { active: false });
  await call('DELETE', `/admin/questions/${q1.id}`);
  assert.equal((await call('GET', `/admin/exams/${e.id}/questions`)).length, 2);
  await call('DELETE', `/admin/exams/${e.id}`);
  assert.equal((await call('GET', '/admin/exams')).length, 2);
  assert.equal(dbNow().questions.filter((q) => q.examId === e.id).length, 0, 'questions removed with the exam');
  await fails(call('DELETE', '/admin/exams/2'), 400, 'validation', 'already has student attempts');
  const used = dbNow().attempts[0].questions[0].qid;
  await call('POST', '/admin/exams/2/active', { active: false });
  await fails(call('DELETE', `/admin/questions/${used}`), 400, 'validation', 'already been used');
});

await test('attempts report, student search and class summary data', async () => {
  await admin();
  const att = await call('GET', '/admin/attempts');
  assert.deepEqual(att.map((a) => [a.student, a.score, a.result]).sort(), [['Alice Johnson', 8, 'PASS'], ['Bob Smith', 5, 'PASS']]);
  assert.equal((await call('GET', '/admin/attempts?examId=1')).length, 0);
  const st = await call('GET', '/admin/students?q=ali');
  assert.deepEqual(st.map((s) => [s.name, s.attempts]), [['Alice Johnson', 1]]);
  assert.equal((await call('GET', '/admin/students')).length, 2);
});

await test('reset restores the sample data and signs everyone out', async () => {
  await admin();
  await call('POST', '/admin/exams', examBody({ title: 'Temporary' }));
  assert.equal((await call('GET', '/admin/exams')).length, 3);
  await call('POST', '/standalone/reset', {});
  await fails(call('GET', '/me'), 401);
  await admin();
  assert.equal((await call('GET', '/admin/exams')).length, 2);
});

await test('works (in memory) when the browser blocks storage, and says so', async () => {
  const blocked = { getItem() { throw new Error('denied'); }, setItem() { throw new Error('denied'); }, removeItem() { throw new Error('denied'); } };
  globalThis.localStorage = blocked;
  const h = await call('GET', '/health');
  await admin();
  assert.equal((await call('GET', '/admin/exams')).length, 2);
  assert.ok((await call('GET', '/health')).message.includes('blocks local storage'));
  void h;
});

// ================================================================================== complete UI journey
section('Complete journey through the real screens (login → exam → result → admin)');

await test('student takes an exam end-to-end in the browser UI and the admin sees the attempt', async () => {
  globalThis.localStorage = makeStorage();
  globalThis.sessionStorage = makeStorage();
  backend.resetForTests();
  dom = installDom();
  timers = installTimers();
  state.user = null;
  await import(W + 'app.js?standalone');
  const routerRender = state.rerender;
  await until(() => dom.getHash() === '#/login');
  await until(() => dom.view.textContent.includes('Standalone mode'));

  const type = (root, t) => root.findAll((e) => e.tagName === 'INPUT' && e.attrs.type === t);
  type(dom.view, 'text')[0].value = 'alice@example.com';
  type(dom.view, 'password')[0].value = 'Student@123';
  await dom.view.byTag('form')[0].fire('submit');
  await until(() => dom.getHash() === '#/student/exams');
  await until(() => dom.view.textContent.includes('Java Fundamentals Quiz'));
  assert.ok(dom.topbar.textContent.includes('Alice Johnson'));

  await dom.view.button('View instructions').click();
  await until(() => dom.getHash() === '#/exam/1');
  await until(() => dom.view.button('Start exam'));
  await type(dom.view, 'checkbox')[0].click();
  await dom.view.button('Start exam').click();
  await until(() => dom.getHash() === '#/take/1');
  await until(() => dom.view.textContent.includes('Question 1 of 10'));

  // answer the first 7 questions correctly (the test reads the key from storage), leave the rest blank
  const key = keyOf();
  const attemptRecord = () => dbNow().attempts.find((a) => a.status === 'IN_PROGRESS');
  const order = attemptRecord().questions.map((q) => q.qid);
  for (let i = 0; i < 7; i++) {
    const radios = type(dom.view, 'radio');
    const target = radios.find((r) => r.value === key[order[i]]);
    await target.click();
    await until(() => attemptRecord().answers[order[i]] === key[order[i]]);
    if (i < 6) await dom.view.button('Next').click();
  }
  assert.ok(dom.view.textContent.includes('Answer saved'));
  await dom.view.button('Submit exam').click();
  assert.ok(dom.overlays()[0].textContent.includes('3 unanswered'));
  await dom.overlays()[0].button('Submit').click();
  await until(() => dom.getHash() === '#/result/' + attemptRecord === undefined ? true : dom.getHash().startsWith('#/result/'));
  await until(() => dom.view.textContent.includes('PASSED'));
  assert.ok(dom.view.textContent.includes('Score: 7 / 10'));
  assert.ok(dom.view.textContent.includes('Percentage: 70%'));
  assert.equal(dbNow().attempts.filter((a) => a.studentId === 1 && a.examId === 1 && a.result).length, 1);

  await dom.topbar.button('Log out').click();
  await until(() => dom.getHash() === '#/login');

  // administrator
  type(dom.view, 'text')[0].value = 'admin';
  await dom.view.button('Administrator').click();
  type(dom.view, 'text')[0].value = 'admin';
  type(dom.view, 'password')[0].value = 'Admin@123';
  await dom.view.byTag('form')[0].fire('submit');
  await until(() => dom.getHash() === '#/admin/exams');
  globalThis.location.hash = '#/admin/attempts';            // the tab is a plain link; a browser would follow it
  await until(() => dom.getHash() === '#/admin/attempts');
  await until(() => dom.view.textContent.includes('Alice Johnson') && dom.view.textContent.includes('7 / 10')).catch((e) => { console.log('VIEW TEXT:', dom.view.textContent.slice(0, 600)); throw e; });
  assert.ok(dom.view.textContent.includes('3 attempt(s) | 3 finished'));
  void routerRender;
});

console.log('\nResult: ' + passed + ' passed, ' + failed + ' failed');
process.exit(failed === 0 ? 0 : 1);
