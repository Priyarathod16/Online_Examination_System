import { h, mount, button, toast, confirmDialog } from '../dom.js';
import { get, post } from '../api.js';
import { state, go } from '../state.js';

/** Left-hand panel of the sign-in screens: a small answer sheet, drawn with plain elements (decorative only). */
function authHero(title, text) {
  const picks = [1, 3, 0, 2, 1];                                   // the bubble that is filled on each row
  const rows = picks.map((pick, i) => h('div', { class: 'omr-row' },
    h('span', { class: 'omr-no', text: String(i + 1) }),
    ['A', 'B', 'C', 'D'].map((letter, n) => h('span', { class: 'omr-b' + (n === pick ? ' on' : ''), text: letter }))));
  return h('aside', { class: 'auth-hero' },
    h('div', null, h('h2', { text: title }), h('p', { text })),
    h('div', { class: 'omr', 'aria-hidden': 'true' }, rows));
}

export function loginView(view) {
  let role = 'student';
  let alive = true;
  const message = h('p', { class: 'form-error', role: 'alert' });
  const idLabel = h('span', { class: 'field-label', text: 'Email' });
  const idInput = h('input', { type: 'text', name: 'identifier', autocomplete: 'username', required: true, maxlength: 150 });
  const pwInput = h('input', { type: 'password', name: 'password', autocomplete: 'current-password', required: true, maxlength: 100 });
  const submit = h('button', { type: 'submit', class: 'btn btn-primary btn-block', text: 'Log in' });
  const dbStatus = h('p', { class: 'muted small', text: 'Checking database connection…' });
  const demoBox = h('div', { class: 'notice small', hidden: true });
  const registerLink = h('a', { href: '#/register', class: 'btn btn-secondary btn-block', text: 'Create a student account' });
  const registerAdminLink = h('a', { href: '#/register-admin', class: 'btn btn-secondary btn-block', text: 'Create an administrator account', style: { display: 'none' } });

  const studentBtn = h('button', { type: 'button', class: 'seg active', 'aria-pressed': 'true', text: 'Student' });
  const adminBtn = h('button', { type: 'button', class: 'seg', 'aria-pressed': 'false', text: 'Administrator' });
  function setRole(next) {
    role = next;
    studentBtn.className = 'seg' + (role === 'student' ? ' active' : '');
    adminBtn.className = 'seg' + (role === 'admin' ? ' active' : '');
    studentBtn.setAttribute('aria-pressed', String(role === 'student'));
    adminBtn.setAttribute('aria-pressed', String(role === 'admin'));
    idLabel.textContent = role === 'admin' ? 'Username' : 'Email';
    registerLink.style.display = role === 'admin' ? 'none' : '';
    registerAdminLink.style.display = role === 'admin' ? '' : 'none';
    message.textContent = '';
  }
  studentBtn.addEventListener('click', () => setRole('student'));
  adminBtn.addEventListener('click', () => setRole('admin'));

  const form = h('form', {
    class: 'card auth-card',
    onSubmit: async (e) => {
      e.preventDefault();
      message.textContent = '';
      submit.disabled = true;
      submit.textContent = 'Signing in…';
      try {
        state.user = await post('/login', { role, identifier: idInput.value, password: pwInput.value });
        pwInput.value = '';
        go(state.user.role === 'admin' ? '#/admin/exams' : '#/student/exams');
      } catch (err) {
        message.textContent = err.message;
        pwInput.value = '';
      } finally {
        submit.disabled = false;
        submit.textContent = 'Log in';
      }
    },
  },
  h('h1', { text: 'Online Examination System' }),
  h('p', { class: 'muted', text: 'Sign in to continue' }),
  h('div', { class: 'segmented' }, studentBtn, adminBtn),
  h('label', { class: 'field' }, idLabel, idInput),
  h('label', { class: 'field' }, h('span', { class: 'field-label', text: 'Password' }), pwInput),
  message, submit, registerLink, registerAdminLink, dbStatus, demoBox);

  mount(view, h('div', { class: 'auth-wrap' },
    authHero('Sit your exam. See your score.', 'Timed multiple-choice exams with instant results and a leaderboard.'), form));
  idInput.focus();

  get('/health').then((r) => {
    if (!alive) return;
    dbStatus.textContent = r.message;
    dbStatus.className = 'small ' + (r.database ? 'ok-text' : 'error-text');
    if (r.standalone) showDemoHelp(demoBox);
  }).catch(() => {
    if (!alive) return;
    dbStatus.textContent = 'Cannot reach the server.';
    dbStatus.className = 'small error-text';
  });
  return () => { alive = false; };
}

/** Standalone mode only: tell the user which sample accounts exist and offer to restore the sample data. */
function showDemoHelp(box) {
  box.hidden = false;
  mount(box,
    h('strong', { text: 'Demo accounts' }),
    h('div', { text: 'Administrator: admin / Admin@123' }),
    h('div', { text: 'To create another administrator, use the key: EXAM-ADMIN-2026' }),
    h('div', { text: 'Student: alice@example.com / Student@123 (or bob@example.com)' }),
    h('div', { class: 'actions' }, button('Reset sample data', 'secondary', async () => {
      if (!(await confirmDialog('Delete everything stored in this browser (accounts, exams, results) and restore the sample data?',
        { okLabel: 'Reset', danger: true, title: 'Reset sample data' }))) return;
      try {
        await post('/standalone/reset');
        toast('Sample data restored.', 'success');
      } catch (err) {
        toast(err.message, 'error');
      }
    })));
}

export function registerView(view) {
  const message = h('p', { class: 'form-error', role: 'alert' });
  const name = h('input', { type: 'text', required: true, maxlength: 100, autocomplete: 'name' });
  const email = h('input', { type: 'email', required: true, maxlength: 150, autocomplete: 'email' });
  const pw = h('input', { type: 'password', required: true, maxlength: 100, autocomplete: 'new-password' });
  const confirm = h('input', { type: 'password', required: true, maxlength: 100, autocomplete: 'new-password' });
  const submit = h('button', { type: 'submit', class: 'btn btn-primary btn-block', text: 'Create account' });

  const form = h('form', {
    class: 'card auth-card',
    onSubmit: async (e) => {
      e.preventDefault();
      message.textContent = '';
      if (pw.value !== confirm.value) {
        message.textContent = 'The two passwords do not match.';
        return;
      }
      submit.disabled = true;
      try {
        await post('/register', { name: name.value, email: email.value, password: pw.value, confirm: confirm.value });
        toast('Your account was created. You can log in now.', 'success');
        go('#/login');
      } catch (err) {
        message.textContent = err.message;
      } finally {
        submit.disabled = false;
      }
    },
  },
  h('h1', { text: 'Create account' }),
  h('p', { class: 'muted', text: 'Password: at least 8 characters with a letter and a digit.' }),
  h('label', { class: 'field' }, h('span', { class: 'field-label', text: 'Full name' }), name),
  h('label', { class: 'field' }, h('span', { class: 'field-label', text: 'Email' }), email),
  h('label', { class: 'field' }, h('span', { class: 'field-label', text: 'Password' }), pw),
  h('label', { class: 'field' }, h('span', { class: 'field-label', text: 'Confirm password' }), confirm),
  message, submit,
  button('Back to login', 'secondary', () => go('#/login'), { class: 'btn btn-secondary btn-block' }));

  mount(view, h('div', { class: 'auth-wrap' },
    authHero('Your first exam is one account away.', 'Register once, then take every exam your administrators open.'), form));
  name.focus();
  return null;
}

/** Creates an administrator account. The server only accepts it together with the administrator registration key. */
export function registerAdminView(view) {
  const message = h('p', { class: 'form-error', role: 'alert' });
  const username = h('input', { type: 'text', required: true, minlength: 3, maxlength: 80, autocomplete: 'username' });
  const pw = h('input', { type: 'password', required: true, maxlength: 100, autocomplete: 'new-password' });
  const confirm = h('input', { type: 'password', required: true, maxlength: 100, autocomplete: 'new-password' });
  const key = h('input', { type: 'password', required: true, maxlength: 100, autocomplete: 'off' });
  const submit = h('button', { type: 'submit', class: 'btn btn-primary btn-block', text: 'Create administrator account' });

  const form = h('form', {
    class: 'card auth-card',
    onSubmit: async (e) => {
      e.preventDefault();
      message.textContent = '';
      if (pw.value !== confirm.value) {
        message.textContent = 'The two passwords do not match.';
        return;
      }
      submit.disabled = true;
      try {
        await post('/register-admin', { username: username.value, password: pw.value, confirm: confirm.value, adminKey: key.value });
        toast('The administrator account was created. You can log in now.', 'success');
        go('#/login');
      } catch (err) {
        message.textContent = err.message;
        key.value = '';
      } finally {
        submit.disabled = false;
      }
    },
  },
  h('h1', { text: 'Create administrator account' }),
  h('p', { class: 'muted', text: 'Letters, digits, dots, underscores and hyphens in the username. Password: at least 8 characters with a letter and a digit.' }),
  h('label', { class: 'field' }, h('span', { class: 'field-label', text: 'Username' }), username),
  h('label', { class: 'field' }, h('span', { class: 'field-label', text: 'Password' }), pw),
  h('label', { class: 'field' }, h('span', { class: 'field-label', text: 'Confirm password' }), confirm),
  h('label', { class: 'field' }, h('span', { class: 'field-label', text: 'Administrator registration key' }), key),
  h('p', { class: 'muted small', text: 'Ask the person who runs this system for the key. It prevents anyone from making themselves an administrator.' }),
  message, submit,
  button('Back to login', 'secondary', () => go('#/login'), { class: 'btn btn-secondary btn-block' }));

  mount(view, h('div', { class: 'auth-wrap' },
    authHero('Run the exams.', 'Administrators create exams and questions, and see every attempt and result.'), form));
  username.focus();
  return null;
}
