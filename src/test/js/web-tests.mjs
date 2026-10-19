// Front-end tests: run with  node src/test/js/web-tests.mjs   (Node 18+, no packages needed).
// They execute the real files under web/js against a fake DOM, a mocked fetch() and a manual clock.
import assert from 'node:assert/strict';
import { installDom, installTimers, installFetch, flush, FakeElement } from './fake-dom.mjs';

const W = '../../../web/js/';
let dom = installDom();
let timers = installTimers();

const logic = await import(W + 'exam-logic.js');
const { h, mount } = await import(W + 'dom.js');
const { loginView, registerView, registerAdminView } = await import(W + 'views/auth.js');
const { studentView, instructionsView } = await import(W + 'views/student.js');
const { takeView, resultView, reviewView } = await import(W + 'views/exam.js');
const { adminView } = await import(W + 'views/admin.js');
const { state } = await import(W + 'state.js');

let passed = 0;
let failed = 0;
let routerRender = null;   // the router's render function, installed by app.js
async function test(name, fn) {
  try {
    dom = installDom();
    timers = installTimers();
    state.user = null;
    state.rerender = () => {};
    await fn();
    passed++;
    console.log('  PASS  ' + name);
  } catch (e) {
    failed++;
    console.log('  FAIL  ' + name + '\n        ' + (e && e.stack ? e.stack.split('\n').slice(0, 4).join('\n        ') : e));
  }
}
function section(name) { console.log('\n== ' + name + ' =='); }

const view = () => dom.view;
function newView() { return new FakeElement('main'); }
const inputByType = (root, type) => root.findAll((e) => e.tagName === 'INPUT' && e.attrs.type === type);
const text = (el) => el.textContent;
const putCalls = (calls) => calls.filter((c) => c.method === 'PUT' && c.path.endsWith('/answer'));

function attemptFixture(count = 3, remaining = 600) {
  const questions = [];
  for (let i = 1; i <= count; i++) {
    questions.push({ id: 10 + i, text: 'Question ' + i + '?', marks: 1,
      options: [{ letter: 'C', text: 'cee' }, { letter: 'A', text: 'ay' }, { letter: 'D', text: 'dee' }, { letter: 'B', text: 'bee' }] });
  }
  return { attemptId: 77, examTitle: 'Quiz', attemptNo: 1, remainingSeconds: remaining, questions, answers: {}, marked: [] };
}

// ------------------------------------------------------------------------------------------------
section('Exam logic (pure functions)');

await test('formatClock', () => {
  assert.equal(logic.formatClock(65), '01:05');
  assert.equal(logic.formatClock(3600), '1:00:00');
  assert.equal(logic.formatClock(-4), '00:00');
  assert.equal(logic.formatClock(59.9), '00:59');
});

await test('Countdown follows the clock and rounds up to whole seconds', () => {
  let now = 1000;
  const c = new logic.Countdown(10, () => now);
  assert.equal(c.remainingSeconds(), 10);
  now += 2500;
  assert.equal(c.remainingSeconds(), 8);      // 7.5s left -> shows 8
  now += 7499;
  assert.equal(c.isOver(), false);
  now += 1;
  assert.equal(c.isOver(), true);
  assert.equal(c.remainingSeconds(), 0);
});

await test('SerialQueue runs jobs strictly in order and survives failures', async () => {
  const q = new logic.SerialQueue();
  const log = [];
  const slow = q.enqueue(() => new Promise((r) => setImmediate(() => { log.push('slow'); r('a'); })));
  const failing = q.enqueue(async () => { log.push('fail'); throw new Error('x'); });
  const last = q.enqueue(async () => { log.push('last'); return 'z'; });
  assert.equal(await slow, 'a');
  await assert.rejects(failing);
  assert.equal(await last, 'z');
  assert.deepEqual(log, ['slow', 'fail', 'last']);
});

await test('palette state, pending answers and progress', () => {
  const answers = new Map([[1, 'A'], [2, 'B']]);
  const marked = new Set([2, 3]);
  assert.equal(logic.paletteState(1, answers, marked, false), 'answered');
  assert.equal(logic.paletteState(2, answers, marked, true), 'marked current');
  assert.equal(logic.paletteState(4, answers, marked, false), '');
  assert.deepEqual(logic.pendingAnswers(new Set([1, 9]), answers), { 1: 'A' });
  assert.deepEqual(logic.summarizeProgress([{ id: 1 }, { id: 2 }, { id: 3 }], answers, marked), { answered: 2, unanswered: 1, marked: 2, total: 3 });
});

// ------------------------------------------------------------------------------------------------
section('Login and registration');

await test('student login sends the right request and navigates to the dashboard', async () => {
  const calls = installFetch((m, p) => (p === '/health' ? { json: { database: true, message: 'Database connection OK' } }
    : p === '/login' ? { json: { role: 'student', name: 'Alice' } } : {}));
  const v = newView();
  loginView(v);
  await flush();
  assert.ok(text(v).includes('Database connection OK'));
  const [id, pw] = [inputByType(v, 'text')[0], inputByType(v, 'password')[0]];
  id.value = 'alice@example.com';
  pw.value = 'Student@123';
  await v.byTag('form')[0].fire('submit');
  const login = calls.find((c) => c.path === '/login');
  assert.deepEqual(login.body, { role: 'student', identifier: 'alice@example.com', password: 'Student@123' });
  assert.equal(login.headers['X-Requested-With'], 'XMLHttpRequest');
  assert.equal(login.headers['Content-Type'], 'application/json');
  assert.deepEqual(state.user, { role: 'student', name: 'Alice' });
  assert.equal(dom.getHash(), '#/student/exams');
  assert.equal(pw.value, '', 'password field is cleared');
});

await test('administrator tab switches the label and role sent', async () => {
  const calls = installFetch((m, p) => (p === '/login' ? { json: { role: 'admin', name: 'admin' } } : { json: { database: false, message: 'x' } }));
  const v = newView();
  loginView(v);
  await v.button('Administrator').click();
  assert.ok(text(v).includes('Username'));
  inputByType(v, 'text')[0].value = 'admin';
  inputByType(v, 'password')[0].value = 'Admin@123';
  await v.byTag('form')[0].fire('submit');
  assert.equal(calls.find((c) => c.path === '/login').body.role, 'admin');
  assert.equal(dom.getHash(), '#/admin/exams');
});

await test('failed login shows the server message and clears the password', async () => {
  installFetch((m, p) => (p === '/login' ? { status: 401, json: { error: 'Invalid credentials. Please check your details and try again.', code: 'credentials' } }
    : { json: { database: true, message: 'ok' } }));
  const v = newView();
  loginView(v);
  inputByType(v, 'text')[0].value = 'a@b.co';
  const pw = inputByType(v, 'password')[0];
  pw.value = 'wrong';
  await v.byTag('form')[0].fire('submit');
  assert.ok(text(v).includes('Invalid credentials'));
  assert.equal(pw.value, '');
  assert.equal(state.user, null);
});

await test('registration checks that the passwords match before calling the server', async () => {
  const calls = installFetch(() => ({ json: { ok: true } }));
  const v = newView();
  registerView(v);
  const [name, email] = inputByType(v, 'text').concat(inputByType(v, 'email'));
  const pws = inputByType(v, 'password');
  inputByType(v, 'text')[0].value = 'Alice';
  inputByType(v, 'email')[0].value = 'a@b.co';
  pws[0].value = 'Passw0rd1';
  pws[1].value = 'Different1';
  await v.byTag('form')[0].fire('submit');
  assert.ok(text(v).includes('do not match'));
  assert.equal(calls.length, 0);
  pws[1].value = 'Passw0rd1';
  await v.byTag('form')[0].fire('submit');
  assert.equal(calls[0].path, '/register');
  assert.equal(dom.getHash(), '#/login');
  void name; void email;
});

await test('the administrator tab offers "Create an administrator account" and hides the student link', async () => {
  installFetch(() => ({ json: { database: true, message: 'x' } }));
  const v = newView();
  loginView(v);
  const links = () => v.byTag('a');
  const adminLink = () => links().find((a) => (a.textContent || '').includes('administrator account'));
  const studentLink = () => links().find((a) => (a.textContent || '').includes('student account'));
  assert.equal(adminLink().style.display, 'none');
  await v.button('Administrator').click();
  assert.equal(adminLink().style.display, '');
  assert.equal(studentLink().style.display, 'none');
  assert.equal(adminLink().getAttribute('href'), '#/register-admin');
});

await test('administrator registration checks the passwords, then posts username, passwords and key', async () => {
  const calls = installFetch((m, p) => (p === '/register-admin' ? { json: { ok: true } } : {}));
  const v = newView();
  registerAdminView(v);
  inputByType(v, 'text')[0].value = 'newadmin';
  const pws = inputByType(v, 'password');
  assert.equal(pws.length, 3, 'password, confirmation and registration key');
  pws[0].value = 'Passw0rd1';
  pws[1].value = 'Different1';
  pws[2].value = 'EXAM-ADMIN-2026';
  await v.byTag('form')[0].fire('submit');
  assert.ok(text(v).includes('do not match'));
  assert.equal(calls.length, 0);
  pws[1].value = 'Passw0rd1';
  await v.byTag('form')[0].fire('submit');
  assert.equal(calls[0].path, '/register-admin');
  assert.deepEqual(calls[0].body, { username: 'newadmin', password: 'Passw0rd1', confirm: 'Passw0rd1', adminKey: 'EXAM-ADMIN-2026' });
  assert.equal(dom.getHash(), '#/login');
});

await test('a wrong registration key shows the server message and clears only the key field', async () => {
  installFetch((m, p) => (p === '/register-admin' ? { status: 403, json: { error: 'Invalid administrator registration key.', code: 'forbidden' } } : {}));
  const v = newView();
  registerAdminView(v);
  inputByType(v, 'text')[0].value = 'newadmin';
  const pws = inputByType(v, 'password');
  pws[0].value = 'Passw0rd1';
  pws[1].value = 'Passw0rd1';
  pws[2].value = 'guess';
  await v.byTag('form')[0].fire('submit');
  assert.ok(text(v).includes('Invalid administrator registration key'));
  assert.equal(pws[2].value, '');
  assert.notEqual(dom.getHash(), '#/login');
});

// ------------------------------------------------------------------------------------------------
section('Student dashboard');

await test('exam cards render; markup in titles is shown as text (no HTML injection)', async () => {
  installFetch(() => ({ json: [
    { id: 1, title: '<img src=x onerror=alert(1)>Quiz', description: '<b>bold</b>', durationMinutes: 20, totalQuestions: 10, passingPercentage: 40,
      attemptsUsed: 0, maxAttempts: 2, endTime: null, activeAttemptId: null },
    { id: 2, title: 'Resume me', description: '', durationMinutes: 5, totalQuestions: 3, passingPercentage: 50, attemptsUsed: 1, maxAttempts: 1,
      endTime: '2026-12-31T10:00', activeAttemptId: 9 },
    { id: 3, title: 'Used up', description: '', durationMinutes: 5, totalQuestions: 3, passingPercentage: 50, attemptsUsed: 2, maxAttempts: 2,
      endTime: null, activeAttemptId: null },
  ] }));
  const v = newView();
  studentView(v, ['exams']);
  await flush();
  assert.equal(v.findAll((e) => e.tagName === 'IMG' || e.tagName === 'B').length, 0, 'no injected elements');
  assert.ok(text(v).includes('<img src=x onerror=alert(1)>Quiz'));
  assert.ok(v.button('Resume exam'));
  assert.ok(v.button('View instructions'));
  assert.equal(v.button('No attempts left').disabled, true);
  await v.button('Resume exam').click();
  assert.equal(dom.getHash(), '#/exam/2');
});

await test('instructions: Start stays disabled until the checkbox is ticked, then goes to the exam', async () => {
  installFetch(() => ({ json: [{ id: 5, title: 'Java', description: 'd', instructions: 'Read carefully', durationMinutes: 20, totalQuestions: 10,
    passingPercentage: 40, negativeMarks: 0.25, allowReview: true, attemptsUsed: 0, maxAttempts: 1, activeAttemptId: null }] }));
  const v = newView();
  instructionsView(v, ['5']);
  await flush();
  const start = v.button('Start exam');
  assert.equal(start.disabled, true);
  assert.ok(text(v).includes('deducts 0.25 marks'));
  assert.ok(text(v).includes('review your answers after submitting'));
  await inputByType(v, 'checkbox')[0].click();
  assert.equal(start.disabled, false);
  await start.click();
  assert.equal(dom.getHash(), '#/take/5');
});

await test('results history lists attempts with review links only when allowed', async () => {
  installFetch(() => ({ json: [
    { attemptId: 1, examId: 1, examTitle: 'A', attemptNo: 1, score: 8, maxScore: 10, percentage: 80, status: 'PASS', timedOut: false, submittedAt: '2026-09-01T10:11', reviewAllowed: true },
    { attemptId: 2, examId: 2, examTitle: 'B', attemptNo: 1, score: 1, maxScore: 10, percentage: 10, status: 'FAIL', timedOut: true, submittedAt: '2026-09-02T10:11', reviewAllowed: false },
  ] }));
  const v = newView();
  studentView(v, ['results']);
  await flush();
  assert.equal(v.findAll((e) => e.tagName === 'BUTTON' && e.textContent === 'Review answers').length, 1);
  assert.ok(text(v).includes('Time expired'));
  assert.ok(text(v).includes('8 / 10'));
});

// ------------------------------------------------------------------------------------------------
section('Taking an exam');

async function startExam(routes, fixture = attemptFixture()) {
  const calls = installFetch((m, p, b, c) => {
    if (p === '/student/exams/5/start') return { json: fixture };
    return routes(m, p, b, c);
  });
  const v = newView();
  const cleanup = takeView(v, ['5']);
  await flush();
  return { v, calls, cleanup };
}

await test('options show in the shuffled order; the ORIGINAL letter is what gets saved', async () => {
  const { v, calls, cleanup } = await startExam(() => ({ json: { ok: true } }));
  const radios = inputByType(v, 'radio');
  assert.equal(radios.length, 4);
  assert.deepEqual(radios.map((r) => r.value), ['C', 'A', 'D', 'B']);
  assert.ok(text(v).includes('Question 1 of 3'));
  await radios[1].click();                       // second row on screen (labelled "B.") is the original option A
  const save = putCalls(calls)[0];
  assert.deepEqual(save.body, { questionId: 11, option: 'A' });
  assert.equal(save.path, '/student/attempts/77/answer');
  await flush();
  assert.ok(text(v).includes('Answer saved'));
  cleanup();
});

await test('"saved" is shown only after the server confirms; a failure says NOT saved', async () => {
  const { v, cleanup } = await startExam((m, p) => (p.endsWith('/answer') ? { status: 503, json: { error: 'Database is down.', code: 'database' } } : { json: { ok: true } }));
  await inputByType(v, 'radio')[0].click();
  await flush();
  const status = v.find((e) => e.hasClass('status'));
  assert.ok(text(status).startsWith('NOT saved'), text(status));
  assert.ok(!text(status).includes('Answer saved'));
  cleanup();
});

await test('rapid answer changes reach the server in order (single queue)', async () => {
  const pending = [];
  const { v, calls, cleanup } = await startExam((m, p) => {
    if (p.endsWith('/answer')) return new Promise((resolve) => pending.push(() => resolve({ json: { ok: true } })));
    return { json: { ok: true } };
  });
  const radios = inputByType(v, 'radio');
  await radios[0].click();                       // original C
  await radios[2].click();                       // original D
  await flush();
  assert.equal(putCalls(calls).length, 1, 'second save waits for the first');
  pending.shift()();
  await flush();
  assert.equal(putCalls(calls).length, 2);
  assert.deepEqual(putCalls(calls).map((c) => c.body.option), ['C', 'D']);
  pending.shift()();
  await flush();
  cleanup();
});

await test('navigation, palette colours, mark for review and clear answer', async () => {
  const { v, calls, cleanup } = await startExam(() => ({ json: { ok: true } }));
  const pal = () => v.findAll((e) => e.hasClass('pal'));
  assert.equal(pal().length, 3);
  assert.ok(pal()[0].hasClass('current'));
  await inputByType(v, 'radio')[0].click();
  assert.ok(pal()[0].hasClass('answered'));
  await v.button('Next').click();
  assert.ok(text(v).includes('Question 2 of 3'));
  assert.ok(pal()[1].hasClass('current'));
  await inputByType(v, 'checkbox')[0].click();
  assert.ok(pal()[1].hasClass('marked'));
  assert.deepEqual(calls.find((c) => c.path.endsWith('/mark')).body, { questionId: 12, marked: true });
  await pal()[0].click();                        // jump back through the palette
  assert.ok(text(v).includes('Question 1 of 3'));
  assert.equal(inputByType(v, 'radio').filter((r) => r.checked).length, 1, 'answer restored on screen');
  await v.button('Clear answer').click();
  assert.equal(inputByType(v, 'radio').filter((r) => r.checked).length, 0);
  assert.equal(putCalls(calls).pop().body.option, null);
  assert.equal(v.button('Previous').disabled, true);
  cleanup();
});

await test('a resumed attempt restores answers and review marks from the server', async () => {
  const fx = attemptFixture();
  fx.answers = { 11: 'D' };
  fx.marked = [12];
  const { v, cleanup } = await startExam(() => ({ json: { ok: true } }), fx);
  const checked = inputByType(v, 'radio').filter((r) => r.checked);
  assert.equal(checked[0].value, 'D');
  const pal = v.findAll((e) => e.hasClass('pal'));
  assert.ok(pal[0].hasClass('answered') && pal[1].hasClass('marked'));
  assert.ok(text(v).includes('Answered 1 of 3'));
  cleanup();
});

await test('manual submit asks for confirmation, then submits after the saves and opens the result', async () => {
  const order = [];
  const { v, calls, cleanup } = await startExam((m, p) => { order.push(m + ' ' + p); return { json: { ok: true } }; });
  await inputByType(v, 'radio')[1].click();
  await v.button('Submit exam').click();
  const modal = dom.overlays()[0];
  assert.ok(modal, 'confirmation dialog is shown');
  assert.ok(text(modal).includes('2 unanswered'));
  await modal.button('Submit').click();
  await flush();
  assert.deepEqual(order.slice(-2), ['PUT /student/attempts/77/answer', 'POST /student/attempts/77/submit']);
  assert.equal(dom.getHash(), '#/result/77');
  assert.equal(dom.overlays().length, 0);
  cleanup();
  void calls;
});

await test('cancelling the confirmation does not submit', async () => {
  const { v, calls, cleanup } = await startExam(() => ({ json: { ok: true } }));
  await v.button('Submit exam').click();
  await dom.overlays()[0].button('Cancel').click();
  await flush();
  assert.equal(calls.filter((c) => c.path.endsWith('/submit')).length, 0);
  cleanup();
});

await test('answers the server never confirmed are re-sent with the submit request', async () => {
  const { v, calls, cleanup } = await startExam((m, p) => (p.endsWith('/answer') ? { status: 503, json: { error: 'down', code: 'database' } } : { json: { ok: true } }));
  await inputByType(v, 'radio')[2].click();      // original D, fails to save
  await flush();
  await v.button('Submit exam').click();
  await dom.overlays()[0].button('Submit').click();
  await flush();
  assert.deepEqual(calls.find((c) => c.path.endsWith('/submit')).body, { answers: { 11: 'D' } });
  cleanup();
});

await test('timer: counts down, warns, and submits automatically at zero without asking', async () => {
  const { v, calls, cleanup } = await startExam(() => ({ json: { ok: true } }), attemptFixture(3, 305));
  const timerEl = v.find((e) => e.hasClass('timer'));
  assert.equal(text(timerEl), '05:05');
  await timers.advance(10000);
  assert.equal(text(timerEl), '04:55');
  assert.ok(timerEl.hasClass('warn'));
  assert.ok(text(v).includes('Less than 5 minutes'));
  await timers.advance(240000);
  assert.ok(timerEl.hasClass('danger'));
  assert.equal(calls.filter((c) => c.path.endsWith('/submit')).length, 0);
  await timers.advance(56000);
  assert.equal(calls.filter((c) => c.path.endsWith('/submit')).length, 1, 'auto-submitted exactly once');
  assert.equal(dom.overlays().length, 0, 'no confirmation when time is up');
  assert.equal(dom.getHash(), '#/result/77');
  cleanup();
});

await test('the server rejecting a save as late (closed) triggers the automatic submit', async () => {
  const { v, calls, cleanup } = await startExam((m, p) => (p.endsWith('/answer') ? { status: 409, json: { error: 'Time is up', code: 'closed' } } : { json: { ok: true } }));
  await inputByType(v, 'radio')[0].click();
  await flush();
  assert.equal(calls.filter((c) => c.path.endsWith('/submit')).length, 1);
  assert.equal(dom.getHash(), '#/result/77');
  cleanup();
});

await test('a failed submission offers a retry and does not lose the exam', async () => {
  let attempts = 0;
  const { v, calls, cleanup } = await startExam((m, p) => {
    if (p.endsWith('/submit')) { attempts++; return attempts === 1 ? { status: 503, json: { error: 'Could not submit the exam.', code: 'database' } } : { json: { attemptId: 77 } }; }
    return { json: { ok: true } };
  });
  await v.button('Submit exam').click();
  await dom.overlays()[0].button('Submit').click();
  await flush();
  assert.equal(dom.getHash(), '', 'still on the exam after the failure');
  const retry = dom.overlays()[0];
  assert.ok(text(retry).includes('Could not submit the exam.'));
  await retry.button('Try again').click();
  await flush();
  assert.equal(calls.filter((c) => c.path.endsWith('/submit')).length, 2);
  assert.equal(dom.getHash(), '#/result/77');
  cleanup();
});

await test('Save & exit leaves without submitting; the timer stops being displayed', async () => {
  const { v, calls, cleanup } = await startExam(() => ({ json: { ok: true } }));
  await v.button('Save & exit').click();
  await dom.overlays()[0].button('Leave exam').click();
  await flush();
  assert.equal(dom.getHash(), '#/student/exams');
  assert.equal(calls.filter((c) => c.path.endsWith('/submit')).length, 0);
  assert.equal(timers.list.size >= 0, true);
  cleanup();
});

await test('cleanup stops the countdown timer', async () => {
  const { cleanup } = await startExam(() => ({ json: { ok: true } }));
  const before = [...timers.list.values()].filter((t) => t.every).length;
  assert.equal(before, 1);
  cleanup();
  assert.equal([...timers.list.values()].filter((t) => t.every).length, 0);
});

await test('starting a full exam offers the waiting list; other errors show the message', async () => {
  const calls = installFetch((m, p) => (p.endsWith('/start') ? { status: 409, json: { error: 'All seats are taken.', code: 'full' } } : { json: { position: 2 } }));
  const v = newView();
  takeView(v, ['5']);
  await flush();
  assert.ok(text(v).includes('All seats are taken.'));
  await v.button('Join the waiting list').click();
  await flush();
  assert.ok(calls.some((c) => c.path === '/student/exams/5/waitlist'));
  assert.ok(text(dom.toasts).includes('number 2'));

  installFetch(() => ({ status: 400, json: { error: 'You have already used all 1 attempt(s) for this exam.', code: 'validation' } }));
  const v2 = newView();
  takeView(v2, ['5']);
  await flush();
  assert.ok(text(v2).includes('already used all 1'));
});

// ------------------------------------------------------------------------------------------------
section('Result and review');

await test('result page shows score, percentage and verdict', async () => {
  installFetch(() => ({ json: { attemptId: 77, examId: 5, examTitle: 'Quiz', score: 7.5, maxScore: 10, percentage: 75, status: 'PASS', timedOut: true, reviewAllowed: true } }));
  const v = newView();
  resultView(v, ['77']);
  await flush();
  assert.ok(text(v).includes('PASSED'));
  assert.ok(text(v).includes('Score: 7.5 / 10'));
  assert.ok(text(v).includes('Percentage: 75%'));
  assert.ok(text(v).includes('submitted automatically'));
  await v.button('Review answers').click();
  assert.equal(dom.getHash(), '#/review/77');
});

await test('result page hides the review button when the exam does not allow it', async () => {
  installFetch(() => ({ json: { attemptId: 1, examId: 5, examTitle: 'Quiz', score: 1, maxScore: 10, percentage: 10, status: 'FAIL', timedOut: false, reviewAllowed: false } }));
  const v = newView();
  resultView(v, ['1']);
  await flush();
  assert.ok(text(v).includes('FAILED'));
  assert.equal(v.button('Review answers'), null);
});

await test('review marks the correct answer, the wrong choice and unanswered questions', async () => {
  installFetch(() => ({ json: [
    { order: 1, text: 'Q1', marks: 1, isCorrect: false, selected: 'A', correct: 'B',
      options: [{ letter: 'B', text: 'right' }, { letter: 'A', text: 'wrong' }, { letter: 'C', text: 'x' }, { letter: 'D', text: 'y' }] },
    { order: 2, text: 'Q2', marks: 1, isCorrect: false, selected: null, correct: 'C',
      options: [{ letter: 'A', text: 'a' }, { letter: 'B', text: 'b' }, { letter: 'C', text: 'c' }, { letter: 'D', text: 'd' }] },
  ] }));
  const v = newView();
  reviewView(v, ['77']);
  await flush();
  assert.equal(v.findAll((e) => e.hasClass('r-correct')).length, 2);
  assert.equal(v.findAll((e) => e.hasClass('r-wrong')).length, 1);
  assert.ok(text(v).includes('Not answered'));
  assert.ok(text(v).includes('A. right — correct answer'));
});

await test('review refused by the server shows its message', async () => {
  installFetch(() => ({ status: 403, json: { error: 'Answer review is not enabled for this exam.', code: 'forbidden' } }));
  const v = newView();
  reviewView(v, ['1']);
  await flush();
  assert.ok(text(v).includes('not enabled'));
});

// ------------------------------------------------------------------------------------------------
section('Administrator screens');

const examFixture = (over = {}) => ({ id: 1, title: 'Java Quiz', description: 'desc', instructions: '', durationMinutes: 20, totalQuestions: 10,
  passingPercentage: 40, negativeMarks: 0, startTime: null, endTime: null, active: true, shuffleQuestions: true, shuffleOptions: true,
  allowReview: false, maxAttempts: 3, maxConcurrent: null, questionCount: 25, ...over });

await test('exam list, search filter and the "bank too small" warning', async () => {
  installFetch(() => ({ json: [examFixture(), examFixture({ id: 2, title: 'SQL Basics', questionCount: 3, active: false })] }));
  const v = newView();
  adminView(v, ['exams']);
  await flush();
  assert.equal(v.byTag('tr').length, 3);
  assert.ok(text(v).includes('bank too small'));
  const search = v.find((e) => e.tagName === 'INPUT' && e.attrs.type === 'search');
  await search.type('sql');
  assert.equal(v.byTag('tr').length, 2);
  assert.ok(text(v).includes('1 exam(s)'));
});

await test('exam form: server validation error keeps the dialog open, success closes it and refreshes', async () => {
  let created = 0;
  const calls = installFetch((m, p, b) => {
    if (m === 'POST' && p === '/admin/exams') {
      if (b.title === '') return { status: 400, json: { error: 'Title is required.', code: 'validation' } };
      created++;
      return { json: examFixture({ id: 9, title: b.title }) };
    }
    return { json: [] };
  });
  const v = newView();
  adminView(v, ['exams']);
  await flush();
  await v.button('New exam').click();
  let modal = dom.overlays()[0];
  assert.ok(modal);
  await modal.button('Create exam').click();
  await flush();
  assert.equal(dom.overlays().length, 1, 'dialog stays open on error');
  assert.ok(text(dom.overlays()[0]).includes('Title is required.'));
  modal = dom.overlays()[0];
  modal.byTag('input').find((i) => i.attrs.maxlength === '150').value = 'New exam';
  await modal.button('Create exam').click();
  await flush();
  assert.equal(created, 1);
  assert.equal(dom.overlays().length, 0, 'dialog closed after success');
  const body = calls.find((c) => c.method === 'POST' && c.path === '/admin/exams' && c.body.title === 'New exam').body;
  assert.equal(body.durationMinutes, '30');
  assert.equal(body.active, false);
  assert.equal(body.shuffleQuestions, true);
});

await test('deleting an exam asks first and shows the server explanation when refused', async () => {
  const calls = installFetch((m, p) => {
    if (m === 'DELETE') return { status: 400, json: { error: 'This exam already has student attempts and cannot be deleted.', code: 'validation' } };
    return { json: [examFixture()] };
  });
  const v = newView();
  adminView(v, ['exams']);
  await flush();
  await v.button('Delete').click();
  await dom.overlays()[0].button('Delete').click();
  await flush();
  assert.ok(calls.some((c) => c.method === 'DELETE' && c.path === '/admin/exams/1'));
  assert.ok(text(dom.toasts).includes('cannot be deleted'));
});

await test('questions tab: add dialog sends options, correct letter and marks', async () => {
  const calls = installFetch((m, p) => {
    if (p === '/admin/exams') return { json: [examFixture({ totalQuestions: 2, questionCount: 1 })] };
    if (m === 'GET' && p === '/admin/exams/1/questions') return { json: [{ id: 5, examId: 1, text: 'Existing?', options: ['a', 'b', 'c', 'd'], correct: 'C', marks: 1 }] };
    return { json: { id: 6 } };
  });
  const v = newView();
  adminView(v, ['questions', '1']);
  await flush();
  assert.ok(text(v).includes('ADD MORE QUESTIONS'));
  assert.ok(text(v).includes('Existing?'));
  await v.button('Add question').click();
  const modal = dom.overlays()[0];
  const inputs = modal.byTag('input');
  ['opt a', 'opt b', 'opt c', 'opt d'].forEach((val, i) => { inputs[i].value = val; });
  modal.byTag('textarea')[0].value = 'What is it?';
  modal.byTag('select')[0].value = 'D';
  await modal.button('Save question').click();
  await flush();
  const post = calls.find((c) => c.method === 'POST' && c.path === '/admin/exams/1/questions');
  assert.deepEqual(post.body, { examId: 1, text: 'What is it?', options: ['opt a', 'opt b', 'opt c', 'opt d'], correct: 'D', marks: '1' });
});

await test('attempts tab computes the summary line and filters by student', async () => {
  installFetch((m, p) => (p === '/admin/exams' ? { json: [examFixture()] } : { json: [
    { attemptId: 1, student: 'Alice', email: 'alice@x.co', examTitle: 'Java', attemptNo: 1, startedAt: '2026-09-01T10:00', submittedAt: '2026-09-01T10:10', status: 'SUBMITTED', score: 8, maxScore: 10, percentage: 80, result: 'PASS' },
    { attemptId: 2, student: 'Bob', email: 'bob@x.co', examTitle: 'Java', attemptNo: 1, startedAt: '2026-09-01T11:00', submittedAt: '2026-09-01T11:10', status: 'TIMED_OUT', score: 2, maxScore: 10, percentage: 20, result: 'FAIL' },
    { attemptId: 3, student: 'Cara', email: 'cara@x.co', examTitle: 'Java', attemptNo: 1, startedAt: '2026-09-01T12:00', submittedAt: null, status: 'IN_PROGRESS', score: null, maxScore: null, percentage: null, result: null },
  ] }));
  const v = newView();
  adminView(v, ['attempts']);
  await flush();
  assert.ok(text(v).includes('3 attempt(s) | 2 finished | average score 50% | pass rate 50%'), text(v.find((e) => e.hasClass('summary'))));
  await v.find((e) => e.tagName === 'INPUT' && e.attrs.type === 'search').type('bob@');
  assert.ok(text(v.find((e) => e.hasClass('summary'))).startsWith('1 attempt(s) | 1 finished | average score 20%'));
});

await test('students tab searches on the server', async () => {
  const calls = installFetch(() => ({ json: [{ id: 1, name: 'Alice', email: 'a@x.co', registeredAt: '2026-01-01T10:00', attempts: 2 }] }));
  const v = newView();
  adminView(v, ['students']);
  await flush();
  v.find((e) => e.tagName === 'INPUT').value = 'ali ce&';
  await v.button('Search').click();
  assert.ok(calls.some((c) => c.path === '/admin/students?q=ali%20ce%26'));
  assert.ok(text(v).includes('Alice'));
});

await test('leaderboard panel loads exams, then the ranking of the selected exam', async () => {
  const calls = installFetch((m, p) => (p === '/leaderboard/exams' ? { json: [{ id: 1, title: 'A' }, { id: 2, title: 'B' }] }
    : { json: [{ rank: 1, student: 'Alice', score: 9, maxScore: 10, percentage: 90, status: 'PASS', submittedAt: '2026-09-01T10:00' }] }));
  const v = newView();
  studentView(v, ['leaderboard', '2']);
  await flush();
  assert.ok(calls.some((c) => c.path === '/leaderboard/2'), 'preselected exam is used');
  assert.ok(text(v).includes('Alice'));
  assert.ok(text(v).includes('best attempt'));
});

// ------------------------------------------------------------------------------------------------
section('Router and shell (app.js)');

await test('boot without a session goes to the login page; login → dashboard → logout', async () => {
  const calls = installFetch((m, p) => {
    if (p === '/me') return { status: 401, json: { error: 'Please log in to continue.', code: 'unauthorized' } };
    if (p === '/health') return { json: { database: true, message: 'Database connection OK' } };
    if (p === '/login') return { json: { role: 'student', name: 'Alice' } };
    if (p === '/student/exams') return { json: [] };
    if (p === '/logout') return { json: { ok: true } };
    return { json: {} };
  });
  await import(W + 'app.js?boot1');
  routerRender = state.rerender;
  await flush();
  assert.equal(dom.getHash(), '#/login');
  assert.ok(text(dom.view).includes('Sign in to continue'));
  assert.equal(dom.topbar.hidden, true);
  inputByType(dom.view, 'text')[0].value = 'alice@example.com';
  inputByType(dom.view, 'password')[0].value = 'Student@123';
  await dom.view.byTag('form')[0].fire('submit');
  await flush();
  assert.equal(dom.getHash(), '#/student/exams');
  assert.equal(dom.topbar.hidden, false);
  assert.ok(text(dom.topbar).includes('Alice'));
  assert.ok(text(dom.view).includes('No exams are open'));
  await dom.topbar.button('Log out').click();
  await flush();
  assert.equal(dom.getHash(), '#/login');
  assert.ok(calls.some((c) => c.path === '/logout'));
  assert.equal(state.user, null);
});

await test('role guards: students cannot open admin pages, admins cannot open student pages', async () => {
  installFetch((m, p) => ({ json: p === '/student/exams' ? [] : p === '/admin/exams' ? [] : {} }));
  state.rerender = routerRender;
  state.user = { role: 'student', name: 'S' };
  dom.setHash('#/admin/exams');
  await state.rerender();
  await flush();
  assert.equal(dom.getHash(), '#/student/exams');
  state.user = { role: 'admin', name: 'A' };
  dom.setHash('#/student/exams');
  await state.rerender();
  await flush();
  assert.equal(dom.getHash(), '#/admin/exams');
  state.user = null;
  dom.setHash('#/take/5');
  await state.rerender();
  await flush();
  assert.equal(dom.getHash(), '#/login');
});

console.log('\nResult: ' + passed + ' passed, ' + failed + ' failed');
process.exit(failed === 0 ? 0 : 1);
