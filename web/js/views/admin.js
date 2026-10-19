import { h, mount, button, badge, dataTable, resultBadge, emptyState, openModal, confirmDialog, toast, fmtNum, fmtDate, fmtScore } from '../dom.js';
import { get, post, put, del } from '../api.js';
import { go } from '../state.js';
import { leaderboardPanel } from './leaderboard.js';

const TABS = [['exams', 'Exams'], ['questions', 'Questions'], ['attempts', 'Attempts & results'], ['leaderboard', 'Leaderboard'], ['students', 'Students']];

function tabs(current) {
  return h('nav', { class: 'tabs', 'aria-label': 'Administrator sections' }, TABS.map(([key, label]) =>
    h('a', { href: '#/admin/' + key, class: 'tab' + (key === current ? ' active' : ''), text: label })));
}

function errorBlock(err, retry) {
  return h('div', { class: 'panel' }, h('p', { class: 'form-error', role: 'alert', text: err.message }),
    retry ? button('Try again', 'secondary', retry) : null);
}

function matches(text, keyword) {
  return !keyword || String(text).toLowerCase().includes(keyword.trim().toLowerCase());
}

export function adminView(view, params) {
  const tab = TABS.some(([k]) => k === params[0]) ? params[0] : 'exams';
  const body = h('div');
  mount(view, h('h1', { text: 'Administrator dashboard' }), tabs(tab), body);
  if (tab === 'exams') examsTab(body);
  else if (tab === 'questions') questionsTab(body, params[1]);
  else if (tab === 'attempts') attemptsTab(body);
  else if (tab === 'leaderboard') {
    mount(body, leaderboardPanel(async () => (await get('/admin/exams')).map((e) => ({ id: e.id, title: e.title })), params[1]));
  } else studentsTab(body);
  return null;
}

// ------------------------------------------------------------------------ exams

async function examsTab(body) {
  mount(body, h('p', { class: 'muted', text: 'Loading exams…' }));
  let exams;
  try {
    exams = await get('/admin/exams');
  } catch (err) {
    mount(body, errorBlock(err, () => examsTab(body)));
    return;
  }
  const search = h('input', { type: 'search', placeholder: 'Search exams…', 'aria-label': 'Search exams' });
  const info = h('span', { class: 'muted' });
  const table = dataTable([
    { label: 'ID', render: (e) => String(e.id) },
    { label: 'Exam', render: (e) => h('div', null, h('strong', { text: e.title }), e.description ? h('div', { class: 'muted small', text: e.description }) : null) },
    { label: 'Duration', render: (e) => e.durationMinutes + ' min' },
    { label: 'Questions (bank / per attempt)', render: (e) => h('span', null, e.questionCount + ' / ' + e.totalQuestions + ' ',
      e.questionCount < e.totalQuestions ? badge('bank too small', 'warn') : null) },
    { label: 'Pass %', render: (e) => fmtNum(e.passingPercentage) },
    { label: 'Neg.', render: (e) => fmtNum(e.negativeMarks) },
    { label: 'Attempts', render: (e) => String(e.maxAttempts) },
    { label: 'Availability', render: (e) => (!e.startTime && !e.endTime ? 'Always' : fmtDate(e.startTime) + ' → ' + fmtDate(e.endTime)) },
    { label: 'Status', render: (e) => badge(e.active ? 'Active' : 'Inactive', e.active ? 'pass' : 'neutral') },
    { label: '', render: (e) => h('div', { class: 'actions' },
      button('Edit', 'secondary', () => examForm(e, () => examsTab(body))),
      button('Questions', 'secondary', () => go('#/admin/questions/' + e.id)),
      button(e.active ? 'Deactivate' : 'Activate', 'secondary', async () => {
        try {
          await post('/admin/exams/' + e.id + '/active', { active: !e.active });
          toast('Exam ' + (e.active ? 'deactivated' : 'activated') + '.', 'success');
          examsTab(body);
        } catch (err) {
          toast(err.message, 'error');
        }
      }),
      button('Delete', 'danger', async () => {
        if (!(await confirmDialog('Delete the exam “' + e.title + '” and all of its questions? This cannot be undone.', { okLabel: 'Delete', danger: true, title: 'Delete exam' }))) return;
        try {
          await del('/admin/exams/' + e.id);
          toast('Exam deleted.', 'success');
          examsTab(body);
        } catch (err) {
          toast(err.message, 'error');
        }
      })) },
  ], exams);
  function applyFilter() {
    const rows = exams.filter((e) => matches(e.title + ' ' + (e.description || ''), search.value));
    table.update(rows);
    info.textContent = rows.length + ' exam(s)';
  }
  search.addEventListener('input', applyFilter);
  mount(body, h('section', { class: 'panel' },
    h('div', { class: 'toolbar' }, search, button('New exam', 'primary', () => examForm(null, () => examsTab(body))), info), table));
  applyFilter();
}

function examForm(exam, onSaved) {
  const e = exam || { title: '', description: '', instructions: '', durationMinutes: 30, totalQuestions: 10, passingPercentage: 40,
    negativeMarks: 0, maxAttempts: 1, maxConcurrent: null, startTime: null, endTime: null, active: false,
    shuffleQuestions: true, shuffleOptions: true, allowReview: false };
  const input = (type, value, extra) => h('input', { type, value: value === null || value === undefined ? '' : String(value), ...(extra || {}) });
  const f = {
    title: input('text', e.title, { maxlength: 150, required: true }),
    description: input('text', e.description, { maxlength: 500 }),
    instructions: h('textarea', { rows: 4, maxlength: 10000, value: e.instructions || '' }),
    durationMinutes: input('number', e.durationMinutes, { min: 1, max: 600 }),
    totalQuestions: input('number', e.totalQuestions, { min: 1, max: 500 }),
    passingPercentage: input('number', e.passingPercentage, { min: 0, max: 100, step: '0.01' }),
    negativeMarks: input('number', e.negativeMarks, { min: 0, step: '0.01' }),
    maxAttempts: input('number', e.maxAttempts, { min: 1, max: 100 }),
    maxConcurrent: input('number', e.maxConcurrent, { min: 1, placeholder: 'unlimited' }),
    startTime: input('datetime-local', e.startTime),
    endTime: input('datetime-local', e.endTime),
  };
  const check = (label, value) => {
    const box = h('input', { type: 'checkbox', checked: !!value });
    return { box, el: h('label', { class: 'check' }, box, h('span', { text: label })) };
  };
  const active = check('Active (visible to students)', e.active);
  const shuffleQ = check('Shuffle question order and pick a random subset', e.shuffleQuestions);
  const shuffleO = check('Shuffle answer options', e.shuffleOptions);
  const review = check('Allow answer review after submission', e.allowReview);
  const message = h('p', { class: 'form-error', role: 'alert' });
  const lab = (text, el, hint) => h('label', { class: 'field' }, h('span', { class: 'field-label', text }), el,
    hint ? h('span', { class: 'field-hint', text: hint }) : null);

  openModal({
    title: exam ? 'Edit exam' : 'New exam',
    wide: true,
    body: h('div', { class: 'form-grid' },
      lab('Title *', f.title), lab('Description', f.description), lab('Instructions', f.instructions),
      lab('Duration (minutes) *', f.durationMinutes), lab('Questions per attempt *', f.totalQuestions),
      lab('Passing percentage *', f.passingPercentage), lab('Negative marks per wrong answer', f.negativeMarks),
      lab('Maximum attempts *', f.maxAttempts), lab('Max. students at once', f.maxConcurrent, 'Blank = unlimited. Extra students use a waiting list.'),
      lab('Available from', f.startTime, 'Server time zone. Blank = no start limit.'), lab('Available until', f.endTime, 'Students may start until this time.'),
      active.el, shuffleQ.el, shuffleO.el, review.el, message),
    actions: [
      { label: 'Cancel', kind: 'secondary' },
      { label: exam ? 'Save changes' : 'Create exam', kind: 'primary', onClick: async () => {
        message.textContent = '';
        const payload = {
          title: f.title.value, description: f.description.value, instructions: f.instructions.value,
          durationMinutes: f.durationMinutes.value, totalQuestions: f.totalQuestions.value,
          passingPercentage: f.passingPercentage.value, negativeMarks: f.negativeMarks.value, maxAttempts: f.maxAttempts.value,
          maxConcurrent: f.maxConcurrent.value, startTime: f.startTime.value, endTime: f.endTime.value,
          active: active.box.checked, shuffleQuestions: shuffleQ.box.checked, shuffleOptions: shuffleO.box.checked, allowReview: review.box.checked,
        };
        try {
          if (exam) await put('/admin/exams/' + exam.id, payload); else await post('/admin/exams', payload);
          toast('Exam saved.', 'success');
          onSaved();
        } catch (err) {
          message.textContent = err.message;
          return false;                 // keep the dialog open
        }
        return true;
      } },
    ],
  });
}

// -------------------------------------------------------------------- questions

async function questionsTab(body, examIdParam) {
  mount(body, h('p', { class: 'muted', text: 'Loading…' }));
  let exams;
  try {
    exams = await get('/admin/exams');
  } catch (err) {
    mount(body, errorBlock(err, () => questionsTab(body, examIdParam)));
    return;
  }
  if (exams.length === 0) {
    mount(body, emptyState('Create an exam first (Exams tab).'));
    return;
  }
  const select = h('select', { 'aria-label': 'Exam' }, exams.map((e) => h('option', { value: String(e.id), text: e.title })));
  if (examIdParam && exams.some((e) => String(e.id) === String(examIdParam))) select.value = String(examIdParam);
  const search = h('input', { type: 'search', placeholder: 'Search questions…', 'aria-label': 'Search questions' });
  const info = h('span', { class: 'muted' });
  const holder = h('div');
  let questions = [];
  let table = null;

  function current() { return exams.find((e) => String(e.id) === select.value); }

  async function load() {
    const exam = current();
    info.textContent = 'Loading…';
    try {
      questions = await get('/admin/exams/' + exam.id + '/questions');
    } catch (err) {
      info.textContent = '';
      mount(holder, errorBlock(err, load));
      return;
    }
    table = dataTable([
      { label: 'ID', render: (q) => String(q.id) },
      { label: 'Question', render: (q) => q.text },
      { label: 'Options', render: (q) => h('ol', { class: 'opt-list', type: 'A' }, q.options.map((o) => h('li', { text: o }))) },
      { label: 'Correct', render: (q) => q.correct },
      { label: 'Marks', render: (q) => fmtNum(q.marks) },
      { label: '', render: (q) => h('div', { class: 'actions' },
        button('Edit', 'secondary', () => questionForm(exam, q, load)),
        button('Delete', 'danger', async () => {
          if (!(await confirmDialog('Delete question ' + q.id + '?', { okLabel: 'Delete', danger: true, title: 'Delete question' }))) return;
          try {
            await del('/admin/questions/' + q.id);
            toast('Question deleted.', 'success');
            await refreshExamsThenLoad();
          } catch (err) {
            toast(err.message, 'error');
          }
        })) },
    ], questions, 'This exam has no questions yet.');
    mount(holder, table);
    filter();
  }

  async function refreshExamsThenLoad() {
    try { exams = await get('/admin/exams'); } catch (e) { /* keep the old list */ }
    await load();
  }

  function filter() {
    const exam = current();
    const rows = questions.filter((q) => matches(q.text + ' ' + q.options.join(' '), search.value));
    table.update(rows);
    const small = questions.length < exam.totalQuestions;
    info.textContent = questions.length + ' question(s) in the bank; each attempt uses ' + exam.totalQuestions
      + (small ? ' — ADD MORE QUESTIONS (bank is too small)' : '');
    info.className = small ? 'error-text' : 'muted';
  }

  select.addEventListener('change', load);
  search.addEventListener('input', () => { if (table) filter(); });
  mount(body, h('section', { class: 'panel' },
    h('div', { class: 'toolbar' }, h('label', { class: 'inline' }, h('span', { text: 'Exam' }), select), search,
      button('Add question', 'primary', () => questionForm(current(), null, refreshExamsThenLoad)), info), holder));
  await load();
}

function questionForm(exam, question, onSaved) {
  const q = question || { text: '', options: ['', '', '', ''], correct: 'A', marks: 1 };
  const text = h('textarea', { rows: 3, maxlength: 5000, value: q.text });
  const opts = q.options.map((o) => h('input', { type: 'text', maxlength: 500, value: o }));
  const correct = h('select', null, ['A', 'B', 'C', 'D'].map((l) => h('option', { value: l, text: l, selected: q.correct === l })));
  correct.value = q.correct;
  const marks = h('input', { type: 'number', min: '0.01', step: '0.01', value: String(q.marks) });
  const message = h('p', { class: 'form-error', role: 'alert' });
  const lab = (t, el) => h('label', { class: 'field' }, h('span', { class: 'field-label', text: t }), el);
  openModal({
    title: question ? 'Edit question' : 'Add question to “' + exam.title + '”',
    wide: true,
    body: h('div', { class: 'form-grid' }, lab('Question *', text),
      opts.map((o, i) => lab('Option ' + 'ABCD'[i] + ' *', o)), lab('Correct option *', correct), lab('Marks *', marks), message),
    actions: [
      { label: 'Cancel', kind: 'secondary' },
      { label: 'Save question', kind: 'primary', onClick: async () => {
        message.textContent = '';
        const payload = { examId: exam.id, text: text.value, options: opts.map((o) => o.value), correct: correct.value, marks: marks.value };
        try {
          if (question) await put('/admin/questions/' + question.id, payload);
          else await post('/admin/exams/' + exam.id + '/questions', payload);
          toast('Question saved.', 'success');
          onSaved();
        } catch (err) {
          message.textContent = err.message;
          return false;
        }
        return true;
      } },
    ],
  });
}

// --------------------------------------------------------------------- attempts

async function attemptsTab(body) {
  mount(body, h('p', { class: 'muted', text: 'Loading…' }));
  let exams;
  try {
    exams = await get('/admin/exams');
  } catch (err) {
    mount(body, errorBlock(err, () => attemptsTab(body)));
    return;
  }
  const select = h('select', { 'aria-label': 'Exam' }, h('option', { value: '', text: 'All exams' }),
    exams.map((e) => h('option', { value: String(e.id), text: e.title })));
  const search = h('input', { type: 'search', placeholder: 'Search student name or email…', 'aria-label': 'Search students' });
  const summary = h('p', { class: 'summary' });
  const holder = h('div');
  let rows = [];
  let table = null;

  function filter() {
    const shown = rows.filter((a) => matches(a.student + ' ' + a.email, search.value));
    table.update(shown);
    const finished = shown.filter((a) => a.percentage !== null);
    const avg = finished.length ? fmtNum(finished.reduce((sum, a) => sum + Number(a.percentage), 0) / finished.length) + '%' : '-';
    const rate = finished.length ? Math.round(100 * finished.filter((a) => a.result === 'PASS').length / finished.length) + '%' : '-';
    summary.textContent = shown.length + ' attempt(s) | ' + finished.length + ' finished | average score ' + avg + ' | pass rate ' + rate;
  }

  async function load() {
    summary.textContent = 'Loading…';
    try {
      rows = await get('/admin/attempts' + (select.value ? '?examId=' + encodeURIComponent(select.value) : ''));
    } catch (err) {
      summary.textContent = '';
      mount(holder, errorBlock(err, load));
      return;
    }
    table = dataTable([
      { label: 'Attempt', render: (a) => String(a.attemptId) },
      { label: 'Student', render: (a) => h('div', null, h('strong', { text: a.student }), h('div', { class: 'muted small', text: a.email })) },
      { label: 'Exam', render: (a) => a.examTitle + ' (#' + a.attemptNo + ')' },
      { label: 'Started', render: (a) => fmtDate(a.startedAt) },
      { label: 'Submitted', render: (a) => fmtDate(a.submittedAt) },
      { label: 'Status', render: (a) => a.status.replace('_', ' ').toLowerCase() },
      { label: 'Score', render: (a) => (a.score === null ? '-' : fmtScore(a.score, a.maxScore)) },
      { label: '%', render: (a) => (a.percentage === null ? '-' : fmtNum(a.percentage) + '%') },
      { label: 'Result', render: (a) => resultBadge(a.result) },
    ], rows, 'No attempts yet.');
    mount(holder, table);
    filter();
  }

  select.addEventListener('change', load);
  search.addEventListener('input', () => { if (table) filter(); });
  mount(body, h('section', { class: 'panel' },
    h('div', { class: 'toolbar' }, h('label', { class: 'inline' }, h('span', { text: 'Exam' }), select), search,
      button('Refresh', 'secondary', load)), holder, summary));
  await load();
}

// --------------------------------------------------------------------- students

function studentsTab(body) {
  const search = h('input', { type: 'search', placeholder: 'Name or email…', 'aria-label': 'Search students' });
  const info = h('span', { class: 'muted' });
  const holder = h('div');
  async function load() {
    info.textContent = 'Searching…';
    try {
      const rows = await get('/admin/students?q=' + encodeURIComponent(search.value));
      info.textContent = rows.length + ' student(s)';
      mount(holder, dataTable([
        { label: 'ID', render: (s) => String(s.id) },
        { label: 'Name', render: (s) => s.name },
        { label: 'Email', render: (s) => s.email },
        { label: 'Registered', render: (s) => fmtDate(s.registeredAt) },
        { label: 'Attempts', render: (s) => String(s.attempts) },
      ], rows, 'No students found.'));
    } catch (err) {
      info.textContent = '';
      mount(holder, errorBlock(err, load));
    }
  }
  search.addEventListener('keydown', (e) => { if (e.key === 'Enter') load(); });
  mount(body, h('section', { class: 'panel' },
    h('div', { class: 'toolbar' }, search, button('Search', 'primary', load), info), holder));
  load();
}
