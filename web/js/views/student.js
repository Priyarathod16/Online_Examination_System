import { h, mount, button, badge, dataTable, resultBadge, emptyState, fmtNum, fmtDate, fmtScore } from '../dom.js';
import { get } from '../api.js';
import { go } from '../state.js';
import { leaderboardPanel } from './leaderboard.js';

const TABS = [['exams', 'Available exams'], ['results', 'My results'], ['leaderboard', 'Leaderboard']];

function tabs(current) {
  return h('nav', { class: 'tabs', 'aria-label': 'Student sections' }, TABS.map(([key, label]) =>
    h('a', { href: '#/student/' + key, class: 'tab' + (key === current ? ' active' : ''), text: label })));
}

function errorBlock(err, retry) {
  return h('div', { class: 'panel' }, h('p', { class: 'form-error', role: 'alert', text: err.message }),
    retry ? button('Try again', 'secondary', retry) : null);
}

export function studentView(view, params) {
  const tab = TABS.some(([k]) => k === params[0]) ? params[0] : 'exams';
  const body = h('div');
  mount(view, h('h1', { text: 'Student dashboard' }), tabs(tab), body);
  if (tab === 'exams') loadExams(body);
  else if (tab === 'results') loadResults(body);
  else mount(body, leaderboardPanel(() => get('/leaderboard/exams'), params[1]));
  return null;
}

async function loadExams(body) {
  mount(body, h('p', { class: 'muted', text: 'Loading exams…' }));
  try {
    const exams = await get('/student/exams');
    if (exams.length === 0) {
      mount(body, emptyState('No exams are open right now. Please check again later.'));
      return;
    }
    mount(body, h('div', { class: 'grid' }, exams.map(examCard)));
  } catch (err) {
    mount(body, errorBlock(err, () => loadExams(body)));
  }
}

function examCard(exam) {
  const resume = exam.activeAttemptId !== null && exam.activeAttemptId !== undefined;
  const usedUp = !resume && exam.attemptsUsed >= exam.maxAttempts;
  const action = usedUp
    ? h('button', { type: 'button', class: 'btn btn-secondary', disabled: true, text: 'No attempts left' })
    : button(resume ? 'Resume exam' : 'View instructions', resume ? 'success' : 'primary', () => go('#/exam/' + exam.id));
  return h('article', { class: 'card exam-card' },
    h('div', { class: 'row-between' }, h('h2', { text: exam.title }), resume ? badge('In progress', 'warn') : null),
    h('p', { class: 'muted', text: exam.description || '' }),
    h('ul', { class: 'chips' },
      h('li', { text: exam.durationMinutes + ' min' }),
      h('li', { text: exam.totalQuestions + ' questions' }),
      h('li', { text: 'Pass mark ' + fmtNum(exam.passingPercentage) + '%' }),
      h('li', { text: 'Attempts ' + exam.attemptsUsed + ' / ' + exam.maxAttempts }),
      exam.endTime ? h('li', { text: 'Closes ' + fmtDate(exam.endTime) }) : null),
    action);
}

async function loadResults(body) {
  mount(body, h('p', { class: 'muted', text: 'Loading results…' }));
  try {
    const rows = await get('/student/history');
    mount(body, h('section', { class: 'panel' }, dataTable([
      { label: 'Exam', render: (r) => r.examTitle },
      { label: 'Attempt', render: (r) => '#' + r.attemptNo },
      { label: 'Score', render: (r) => fmtScore(r.score, r.maxScore) },
      { label: 'Percentage', render: (r) => fmtNum(r.percentage) + '%' },
      { label: 'Result', render: (r) => resultBadge(r.status) },
      { label: 'Finished', render: (r) => (r.timedOut ? 'Time expired' : 'Submitted') + ' · ' + fmtDate(r.submittedAt) },
      { label: '', render: (r) => h('div', { class: 'actions' },
        button('View', 'secondary', () => go('#/result/' + r.attemptId)),
        r.reviewAllowed ? button('Review answers', 'secondary', () => go('#/review/' + r.attemptId)) : null) },
    ], rows, 'You have not completed any exams yet.')));
  } catch (err) {
    mount(body, errorBlock(err, () => loadResults(body)));
  }
}

/** Instructions screen shown before an attempt starts. */
export function instructionsView(view, params) {
  const examId = params[0];
  mount(view, h('p', { class: 'muted', text: 'Loading…' }));
  get('/student/exams').then((exams) => {
    const exam = exams.find((e) => String(e.id) === String(examId));
    if (!exam) {
      mount(view, h('div', { class: 'card' }, h('p', { text: 'This exam is not available right now.' }),
        button('Back to exams', 'primary', () => go('#/student/exams'))));
      return;
    }
    renderInstructions(view, exam);
  }).catch((err) => mount(view, errorBlock(err, () => go('#/student/exams'))));
  return null;
}

function renderInstructions(view, exam) {
  const resume = exam.activeAttemptId !== null && exam.activeAttemptId !== undefined;
  const negative = Number(exam.negativeMarks) > 0;
  const rules = [
    'Each question has exactly one correct answer.',
    'Your answer is saved to the server as soon as you select it.',
    'Use Previous / Next or the question palette to move around, and “Mark for review” to flag questions.',
    'The timer is enforced by the server using the deadline recorded when you start. When it reaches zero your attempt is submitted automatically.',
    'If you leave or your connection drops, you can resume before the deadline; the timer keeps running.',
    negative ? 'Each wrong answer deducts ' + fmtNum(exam.negativeMarks) + ' marks. Unanswered questions score zero.'
      : 'There is no negative marking. Unanswered questions score zero.',
    exam.allowReview ? 'You will be able to review your answers after submitting.' : 'Correct answers are not shown after submission for this exam.',
  ];
  const agree = h('input', { type: 'checkbox', id: 'agree' });
  const start = h('button', { type: 'button', class: 'btn btn-success', disabled: true, text: resume ? 'Resume exam' : 'Start exam' });
  agree.addEventListener('change', () => { start.disabled = !agree.checked; });
  start.addEventListener('click', () => go('#/take/' + exam.id));
  mount(view, h('article', { class: 'card instructions' },
    h('h1', { text: exam.title }),
    h('p', { class: 'muted', text: exam.description || '' }),
    h('ul', { class: 'chips' },
      h('li', { text: 'Duration ' + exam.durationMinutes + ' min' }),
      h('li', { text: exam.totalQuestions + ' questions' }),
      h('li', { text: 'Pass mark ' + fmtNum(exam.passingPercentage) + '%' }),
      h('li', { text: 'Attempts used ' + exam.attemptsUsed + ' of ' + exam.maxAttempts })),
    exam.instructions ? h('div', { class: 'notice pre', text: exam.instructions }) : null,
    h('h2', { text: 'General rules' }),
    h('ul', { class: 'rules' }, rules.map((r) => h('li', { text: r }))),
    h('label', { class: 'check' }, agree, h('span', { text: 'I have read and understood the instructions' })),
    h('div', { class: 'actions' }, start, button('Back', 'secondary', () => go('#/student/exams')))));
}

