import { h, mount, button, badge, resultBadge, confirmDialog, toast, fmtNum } from '../dom.js';
import { get, post, put } from '../api.js';
import { go } from '../state.js';
import { Countdown, SerialQueue, formatClock, paletteState, pendingAnswers, summarizeProgress } from '../exam-logic.js';

/**
 * The exam screen. The countdown is only a display; the real deadline is stored by the server and checked on every
 * save and on submit. An answer is only reported as "saved" after the server confirmed it.
 */
export function takeView(view, params) {
  const examId = params[0];
  let stopTimer = () => {};
  let alive = true;
  mount(view, h('p', { class: 'muted', text: 'Starting your exam…' }));

  post('/student/exams/' + encodeURIComponent(examId) + '/start').then((attempt) => {
    if (alive) stopTimer = runExam(view, attempt);
  }).catch(async (err) => {
    if (!alive) return;
    if (err.code === 'full') {
      mount(view, h('div', { class: 'card' }, h('h2', { text: 'The exam is full' }), h('p', { text: err.message }),
        h('div', { class: 'actions' },
          button('Join the waiting list', 'primary', async () => {
            try {
              const r = await post('/student/exams/' + encodeURIComponent(examId) + '/waitlist');
              toast('You are number ' + r.position + ' in the waiting list. Come back and press Start when a seat is free.', 'success');
            } catch (e) {
              toast(e.message, 'error');
            }
          }),
          button('Back', 'secondary', () => go('#/student/exams')))));
      return;
    }
    mount(view, h('div', { class: 'card' }, h('h2', { text: 'Cannot start the exam' }),
      h('p', { class: 'form-error', role: 'alert', text: err.message }),
      h('div', { class: 'actions' }, button('Back to my exams', 'primary', () => go('#/student/exams')))));
  });
  return () => {
    alive = false;
    stopTimer();
  };
}

function runExam(view, attempt) {
  const questions = attempt.questions;
  const answers = new Map(Object.entries(attempt.answers).map(([id, letter]) => [Number(id), letter]));
  const marked = new Set(attempt.marked);
  const unsaved = new Set();
  const queue = new SerialQueue();
  const countdown = new Countdown(attempt.remainingSeconds);
  const base = '/student/attempts/' + attempt.attemptId;
  let index = 0;
  let submitting = false;
  let warned = false;
  let timerId = null;
  let currentInputs = [];
  let labelByLetter = new Map();

  // ---- static parts of the screen
  const timerEl = h('div', { class: 'timer', role: 'timer', 'aria-label': 'Time remaining', text: formatClock(countdown.remainingSeconds()) });
  const progressEl = h('span', { class: 'muted' });
  const headerEl = h('h2', { class: 'q-head' });
  const textEl = h('p', { class: 'q-text' });
  const optionsEl = h('div', { class: 'options', role: 'radiogroup', 'aria-label': 'Answer options' });
  const markBox = h('input', { type: 'checkbox', id: 'mark' });
  const statusEl = h('p', { class: 'status muted', role: 'status', 'aria-live': 'polite' });
  const prevBtn = button('Previous', 'secondary', () => show(index - 1));
  const nextBtn = button('Next', 'primary', () => show(index + 1));
  const clearBtn = button('Clear answer', 'secondary', clearAnswer);
  const submitBtn = button('Submit exam', 'success', () => submit(false), { class: 'btn btn-success btn-block' });
  const exitBtn = button('Save & exit', 'secondary', saveAndExit, { class: 'btn btn-secondary btn-block' });
  const paletteEl = h('div', { class: 'palette' });
  const paletteButtons = questions.map((q, i) =>
    h('button', { type: 'button', class: 'pal', text: String(i + 1), 'aria-label': 'Go to question ' + (i + 1), onClick: () => show(i) }));
  paletteEl.append(...paletteButtons);
  markBox.addEventListener('change', toggleMark);

  mount(view, h('div', { class: 'exam' },
    h('div', { class: 'exam-bar' }, h('div', null, h('h1', { text: attempt.examTitle }), h('span', { class: 'muted', text: 'Attempt ' + attempt.attemptNo })), timerEl),
    h('div', { class: 'exam-layout' },
      h('section', { class: 'card q-card' },
        h('div', { class: 'row-between' }, headerEl, progressEl), textEl, optionsEl,
        h('div', { class: 'actions' }, prevBtn, nextBtn, clearBtn,
          h('label', { class: 'check' }, markBox, h('span', { text: 'Mark for review' })))),
      h('aside', { class: 'card side' }, h('h2', { text: 'Questions' }), paletteEl,
        h('ul', { class: 'legend' }, h('li', { class: 'l-answered', text: 'Answered' }), h('li', { class: 'l-marked', text: 'Marked for review' }),
          h('li', { class: 'l-none', text: 'Not answered' })),
        submitBtn, exitBtn)),
    statusEl));

  // ---- rendering
  function setStatus(text, kind) {
    statusEl.textContent = text;
    statusEl.className = 'status ' + (kind || 'muted');
  }

  function show(i) {
    if (i < 0 || i >= questions.length) return;
    index = i;
    const q = questions[i];
    const selected = answers.get(q.id);
    headerEl.textContent = 'Question ' + (i + 1) + ' of ' + questions.length + ' (' + fmtNum(q.marks) + (Number(q.marks) === 1 ? ' mark)' : ' marks)');
    textEl.textContent = q.text;
    currentInputs = [];
    labelByLetter = new Map();
    optionsEl.replaceChildren(...q.options.map((opt, pos) => {
      const input = h('input', { type: 'radio', name: 'option', value: opt.letter, checked: selected === opt.letter, disabled: submitting });
      input.addEventListener('change', () => choose(opt.letter));
      const label = h('label', { class: 'option' + (selected === opt.letter ? ' selected' : '') }, input,
        h('span', { class: 'letter', text: String.fromCharCode(65 + pos) }), h('span', { text: opt.text }));
      currentInputs.push(input);
      labelByLetter.set(opt.letter, label);
      return label;
    }));
    markBox.checked = marked.has(q.id);
    prevBtn.disabled = i === 0 || submitting;
    nextBtn.disabled = i === questions.length - 1 || submitting;
    renderPalette();
  }

  function renderPalette() {
    questions.forEach((q, i) => {
      paletteButtons[i].className = 'pal ' + paletteState(q.id, answers, marked, i === index);
    });
    const p = summarizeProgress(questions, answers, marked);
    progressEl.textContent = 'Answered ' + p.answered + ' of ' + p.total;
  }

  // ---- actions
  function choose(letter) {
    if (submitting) return;
    const q = questions[index];
    answers.set(q.id, letter);
    unsaved.add(q.id);
    for (const [l, label] of labelByLetter) label.className = 'option' + (l === letter ? ' selected' : '');
    renderPalette();
    save(q.id, letter);
  }

  function clearAnswer() {
    if (submitting) return;
    const q = questions[index];
    if (!answers.has(q.id)) return;
    answers.delete(q.id);
    unsaved.delete(q.id);
    show(index);
    save(q.id, null);
  }

  function save(questionId, letter) {
    setStatus('Saving…', 'muted');
    return queue.enqueue(async () => {
      try {
        await put(base + '/answer', { questionId, option: letter });
        if (letter === null || answers.get(questionId) === letter) unsaved.delete(questionId);
        setStatus('Answer saved', 'ok-text');
      } catch (err) {
        if (err.code === 'closed') {
          submit(true);                       // the server says the deadline has passed
          return;
        }
        if (letter !== null) unsaved.add(questionId);
        setStatus('NOT saved — ' + err.message + ' It will be retried when you submit.', 'error-text');
      }
    });
  }

  function toggleMark() {
    if (submitting) return;
    const q = questions[index];
    const flag = markBox.checked;
    if (flag) marked.add(q.id); else marked.delete(q.id);
    renderPalette();
    queue.enqueue(async () => {
      try {
        await put(base + '/mark', { questionId: q.id, marked: flag });
      } catch (err) {
        setStatus('The review mark was not saved on the server (it is kept on this screen only).', 'error-text');
      }
    });
  }

  async function saveAndExit() {
    if (submitting) return;
    const ok = await confirmDialog('Your answers are already saved on the server. The timer keeps running while you are away; '
      + 'you can resume from the exam list before the deadline.\nLeave the exam now?', { okLabel: 'Leave exam', title: 'Leave exam' });
    if (!ok) return;
    for (const id of unsaved) if (answers.has(id)) save(id, answers.get(id));   // best-effort flush, queued
    stop();
    go('#/student/exams');
  }

  async function submit(auto) {
    if (submitting) return;
    if (!auto) {
      const p = summarizeProgress(questions, answers, marked);
      const lines = ['Submit your exam now?'];
      if (p.unanswered > 0) lines.push('You have ' + p.unanswered + ' unanswered question(s).');
      if (p.marked > 0) lines.push('You marked ' + p.marked + ' question(s) for review.');
      lines.push('You cannot change your answers after submitting.');
      if (!(await confirmDialog(lines.join('\n'), { okLabel: 'Submit', title: 'Submit exam' }))) return;
      if (submitting) return;
    }
    submitting = true;
    stop();
    for (const el of [prevBtn, nextBtn, clearBtn, submitBtn, exitBtn, markBox]) el.disabled = true;
    for (const input of currentInputs) input.disabled = true;
    setStatus(auto ? 'Time is up — submitting your exam…' : 'Submitting your exam…', 'muted');
    try {
      await queue.enqueue(() => post(base + '/submit', { answers: pendingAnswers(unsaved, answers) }));
      go('#/result/' + attempt.attemptId);
    } catch (err) {
      submitting = false;
      setStatus('Submission was NOT confirmed.', 'error-text');
      const retry = await confirmDialog(err.message + '\nYour answers saved so far are safe. Try submitting again?',
        { okLabel: 'Try again', title: 'Submission failed' });
      if (retry) submit(true); else go('#/student/exams');
    }
  }

  // ---- timer
  function tick() {
    const secs = countdown.remainingSeconds();
    timerEl.textContent = formatClock(secs);
    timerEl.className = 'timer' + (secs <= 60 ? ' danger' : secs <= 300 ? ' warn' : '');
    if (secs <= 300 && secs > 0 && !warned) {
      warned = true;
      setStatus('Less than 5 minutes remaining.', 'warn-text');
    }
    if (countdown.isOver() && !submitting) submit(true);
  }

  function stop() {
    if (timerId !== null) clearInterval(timerId);
    timerId = null;
  }

  show(0);
  tick();
  timerId = setInterval(tick, 250);
  return stop;
}

// ---------------------------------------------------------------------- result / review

export function resultView(view, params) {
  const attemptId = params[0];
  mount(view, h('p', { class: 'muted', text: 'Loading result…' }));
  get('/student/attempts/' + encodeURIComponent(attemptId) + '/result').then((r) => {
    const pass = r.status === 'PASS';
    mount(view, h('article', { class: 'card result' },
      h('p', { class: 'muted', text: 'Exam result' }),
      h('h1', { text: r.examTitle }),
      h('p', { class: 'verdict ' + (pass ? 'ok-text' : 'error-text'), text: pass ? 'PASSED' : 'FAILED' }),
      h('p', { class: 'big', text: 'Score: ' + fmtNum(r.score) + ' / ' + fmtNum(r.maxScore) }),
      h('p', { class: 'big', text: 'Percentage: ' + fmtNum(r.percentage) + '%' }),
      r.timedOut ? h('p', { class: 'muted', text: 'The time limit was reached, so the exam was submitted automatically.' }) : null,
      h('p', { class: 'muted', text: r.reviewAllowed ? 'You can review your answers.' : 'Answer review is not available for this exam.' }),
      h('div', { class: 'actions' },
        button('Back to dashboard', 'primary', () => go('#/student/exams')),
        button('Leaderboard', 'secondary', () => go('#/student/leaderboard/' + r.examId)),
        r.reviewAllowed ? button('Review answers', 'success', () => go('#/review/' + r.attemptId)) : null)));
  }).catch((err) => mount(view, h('div', { class: 'card' }, h('p', { class: 'form-error', role: 'alert', text: err.message }),
    button('Back', 'secondary', () => go('#/student/results')))));
  return null;
}

export function reviewView(view, params) {
  const attemptId = params[0];
  mount(view, h('p', { class: 'muted', text: 'Loading review…' }));
  get('/student/attempts/' + encodeURIComponent(attemptId) + '/review').then((items) => {
    mount(view, h('h1', { text: 'Answer review' }),
      h('div', { class: 'actions' }, button('Back to results', 'secondary', () => go('#/student/results'))),
      items.map((item) => h('article', { class: 'card review-item' },
        h('div', { class: 'row-between' },
          h('h2', { text: 'Question ' + item.order }),
          item.selected === null ? badge('Not answered', 'warn') : resultBadge(item.isCorrect ? 'PASS' : 'FAIL'),
          h('span', { class: 'muted', text: fmtNum(item.marks) + ' marks' })),
        h('p', { class: 'q-text', text: item.text }),
        h('ul', { class: 'review-options' }, item.options.map((o, pos) => {
          const isCorrect = o.letter === item.correct;
          const isChosen = o.letter === item.selected;
          const note = isCorrect && isChosen ? ' — your answer (correct)' : isCorrect ? ' — correct answer' : isChosen ? ' — your answer' : '';
          return h('li', { class: isCorrect ? 'r-correct' : isChosen ? 'r-wrong' : '' },
            String.fromCharCode(65 + pos) + '. ' + o.text + note);
        })))));
  }).catch((err) => mount(view, h('div', { class: 'card' }, h('p', { class: 'form-error', role: 'alert', text: err.message }),
    button('Back', 'secondary', () => go('#/student/results')))));
  return null;
}

