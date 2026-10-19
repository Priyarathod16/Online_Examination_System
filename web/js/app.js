// Application shell: session restore, hash router, top bar.
import { h, mount, button, toast } from './dom.js';
import { get, post, onUnauthorized } from './api.js';
import { state, go } from './state.js';
import { loginView, registerView, registerAdminView } from './views/auth.js';
import { studentView, instructionsView } from './views/student.js';
import { takeView, resultView, reviewView } from './views/exam.js';
import { adminView } from './views/admin.js';

const view = document.getElementById('view');
const topbar = document.getElementById('topbar');
let cleanup = null;
let renderToken = 0;

const PUBLIC = new Set(['login', 'register', 'register-admin']);
const STUDENT_ONLY = new Set(['student', 'exam', 'take', 'result', 'review']);

function parseHash() {
  const raw = (location.hash || '').replace(/^#\/?/, '');
  return raw.split('/').filter(Boolean).map((p) => decodeURIComponent(p));
}

function home() {
  if (!state.user) return '#/login';
  return state.user.role === 'admin' ? '#/admin/exams' : '#/student/exams';
}

function renderTopbar(name) {
  if (!state.user || name === 'take' || PUBLIC.has(name)) {
    topbar.hidden = true;
    mount(topbar);
    return;
  }
  const links = state.user.role === 'admin'
    ? [['#/admin/exams', 'Admin', 'admin']]
    : [['#/student/exams', 'Student', 'student']];
  topbar.hidden = false;
  mount(topbar,
    h('a', { class: 'brand', href: home(), text: 'Online Examination System' }),
    h('nav', { class: 'topnav' }, links.map(([href, label, key]) =>
      h('a', { href, class: name === key ? 'active' : '', text: label }))),
    h('div', { class: 'user' },
      h('span', { text: state.user.name }),
      button('Log out', 'secondary', logout)));
}

async function logout() {
  try {
    await post('/logout');
  } catch (e) {
    // the cookie is cleared server-side when possible; either way we leave the signed-in screens
  }
  state.user = null;
  go('#/login');
}

async function render() {
  const token = ++renderToken;
  if (cleanup) {
    try { cleanup(); } catch (e) { /* ignore */ }
    cleanup = null;
  }
  const [name, ...params] = parseHash();

  if (!name) return go(home());
  if (!PUBLIC.has(name) && !state.user) return go('#/login');
  if (PUBLIC.has(name) && state.user) return go(home());
  if (state.user && state.user.role === 'admin' && STUDENT_ONLY.has(name)) return go(home());
  if (state.user && state.user.role !== 'admin' && name === 'admin') return go(home());

  renderTopbar(name);
  let result = null;
  switch (name) {
    case 'login': result = loginView(view); break;
    case 'register': result = registerView(view); break;
    case 'register-admin': result = registerAdminView(view); break;
    case 'student': result = studentView(view, params); break;
    case 'exam': result = instructionsView(view, params); break;
    case 'take': result = takeView(view, params); break;
    case 'result': result = resultView(view, params); break;
    case 'review': result = reviewView(view, params); break;
    case 'admin': result = adminView(view, params); break;
    default:
      mount(view, h('div', { class: 'card' }, h('h1', { text: 'Page not found' }), button('Go to start', 'primary', () => go(home()))));
  }
  if (token === renderToken) cleanup = typeof result === 'function' ? result : null;
  else if (typeof result === 'function') result();
  window.scrollTo(0, 0);
}

onUnauthorized(() => {
  if (!state.user) return;
  state.user = null;
  toast('Your session has expired. Please log in again.', 'error');
  go('#/login');
});

state.rerender = render;
window.addEventListener('hashchange', render);

(async function boot() {
  try {
    state.user = await get('/me');
  } catch (e) {
    state.user = null;
  }
  render();
})();
