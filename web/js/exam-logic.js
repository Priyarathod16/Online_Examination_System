// Pure helpers used by the exam screen (kept free of DOM code so they can be unit tested with Node).

/** mm:ss or h:mm:ss; never negative. */
export function formatClock(totalSeconds) {
  const s = Math.max(0, Math.floor(totalSeconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  const two = (n) => String(n).padStart(2, '0');
  return h > 0 ? h + ':' + two(m) + ':' + two(sec) : two(m) + ':' + two(sec);
}

/**
 * Countdown that follows a monotonic clock. The server deadline is authoritative; this only drives the display
 * and tells the screen when to submit automatically.
 */
export class Countdown {
  constructor(remainingSeconds, now = () => performance.now()) {
    this.now = now;
    this.deadline = now() + remainingSeconds * 1000;
  }

  remainingMs() {
    return this.deadline - this.now();
  }

  /** Whole seconds left, rounded up so the display reaches 00:00 exactly when time is over. */
  remainingSeconds() {
    return Math.max(0, Math.ceil(this.remainingMs() / 1000));
  }

  isOver() {
    return this.remainingMs() <= 0;
  }
}

/**
 * Runs asynchronous jobs strictly one after another (in the order they were queued). Used so that answer saves reach
 * the server in the order the student made them, and the final submit runs after the saves that preceded it.
 * enqueue() returns a promise for the job's own result; a failing job does not block the jobs behind it.
 */
export class SerialQueue {
  constructor() {
    this.tail = Promise.resolve();
  }

  enqueue(job) {
    const result = this.tail.then(job);
    this.tail = result.catch(() => undefined);
    return result;
  }
}

/** CSS state for a question number in the palette. */
export function paletteState(questionId, answers, marked, isCurrent) {
  const parts = [];
  if (marked.has(questionId)) parts.push('marked');
  else if (answers.has(questionId)) parts.push('answered');
  if (isCurrent) parts.push('current');
  return parts.join(' ');
}

/** Answers the server has not confirmed yet, as the plain object the submit endpoint expects. */
export function pendingAnswers(unsaved, answers) {
  const out = {};
  for (const id of unsaved) {
    if (answers.has(id)) out[id] = answers.get(id);
  }
  return out;
}

export function summarizeProgress(questions, answers, marked) {
  let answered = 0;
  for (const q of questions) if (answers.has(q.id)) answered += 1;
  return { answered, unanswered: questions.length - answered, marked: marked.size, total: questions.length };
}
