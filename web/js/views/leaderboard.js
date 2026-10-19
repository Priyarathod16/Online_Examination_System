import { h, mount, dataTable, resultBadge, fmtNum, fmtDate, emptyState } from '../dom.js';
import { get } from '../api.js';

/**
 * Exam picker + ranked table. loadExams() resolves to [{id, title}].
 * The ranking itself (best attempt per student, sorted in Java) comes from /api/leaderboard/{examId}.
 */
export function leaderboardPanel(loadExams, preselectId) {
  const select = h('select', { 'aria-label': 'Exam' });
  const info = h('span', { class: 'muted', text: '' });
  const holder = h('div');
  let token = 0;

  async function loadRanking() {
    const mine = ++token;
    if (!select.value) {
      mount(holder, emptyState('No exams available.'));
      return;
    }
    info.textContent = 'Loading…';
    try {
      const rows = await get('/leaderboard/' + encodeURIComponent(select.value));
      if (mine !== token) return;
      info.textContent = rows.length ? rows.length + ' student(s) ranked' : '';
      mount(holder, dataTable([
        { label: 'Rank', render: (r) => String(r.rank) },
        { label: 'Student', render: (r) => r.student },
        { label: 'Score', render: (r) => fmtNum(r.score) + ' / ' + fmtNum(r.maxScore) },
        { label: 'Percentage', render: (r) => fmtNum(r.percentage) + '%' },
        { label: 'Result', render: (r) => resultBadge(r.status) },
        { label: 'Submitted', render: (r) => fmtDate(r.submittedAt) },
      ], rows, 'No results yet for this exam.'));
    } catch (err) {
      if (mine !== token) return;
      info.textContent = '';
      mount(holder, h('p', { class: 'form-error', role: 'alert', text: err.message }));
    }
  }

  select.addEventListener('change', loadRanking);
  const el = h('section', { class: 'panel' },
    h('div', { class: 'toolbar' }, h('label', { class: 'inline' }, h('span', { text: 'Exam' }), select), info),
    holder,
    h('p', { class: 'muted small', text: 'Each student appears once with their best attempt. Ties are broken by percentage, then by earlier submission.' }));

  loadExams().then((exams) => {
    select.replaceChildren(...exams.map((e) => h('option', { value: String(e.id), text: e.title })));
    if (preselectId && exams.some((e) => String(e.id) === String(preselectId))) select.value = String(preselectId);
    loadRanking();
  }).catch((err) => mount(holder, h('p', { class: 'form-error', role: 'alert', text: err.message })));
  return el;
}
