// Runs the generated single-file website (standalone/OnlineExamSystem.html) exactly as shipped, against a fake browser,
// and drives a real login + exam + admin check through it.  Run: node scripts/build-standalone.mjs && node src/test/js/bundle-test.mjs
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const realSetTimeout = globalThis.setTimeout;
const { installDom, installTimers, flush } = await import('./fake-dom.mjs');
const file = join(dirname(fileURLToPath(import.meta.url)), '../../../standalone/OnlineExamSystem.html');
const html = readFileSync(file, 'utf8');

assert.ok(!/type="module"/.test(html), 'no ES modules (they cannot load from file://)');
assert.ok(!/(src|href)="(https?:)?\/\//.test(html), 'no external resources: works offline');
assert.ok(html.includes('<title>Online Examination System</title>') && html.includes('<main id="view"'), 'page shell present');
const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)];
assert.equal(scripts.length, 1, 'exactly one inline script');

function makeStorage() {
  const m = new Map();
  return { getItem: (k) => (m.has(k) ? m.get(k) : null), setItem: (k, v) => { m.set(k, String(v)); }, removeItem: (k) => { m.delete(k); } };
}
globalThis.localStorage = makeStorage();
globalThis.sessionStorage = makeStorage();
const dom = installDom();
installTimers();
globalThis.fetch = async () => { throw new Error('standalone mode must never use the network'); };
vm.runInThisContext(scripts[0][1], { filename: 'OnlineExamSystem.html' });

const wait = (ms) => new Promise((r) => realSetTimeout(r, ms));
async function until(cond, label, ms = 8000) {
  const end = Date.now() + ms;
  while (Date.now() < end) { if (cond()) return; await wait(5); await flush(); }
  throw new Error('timed out waiting for: ' + label + '\nVIEW: ' + dom.view.textContent.slice(0, 300));
}
const inputs = (t) => dom.view.findAll((e) => e.tagName === 'INPUT' && e.attrs.type === t);
const text = () => dom.view.textContent;
let step = 0;
const ok = (msg) => console.log('  PASS  ' + (++step) + '. ' + msg);

await until(() => dom.getHash() === '#/login', 'login route');
await until(() => text().includes('Standalone mode'), 'standalone health message');
assert.ok(text().includes('admin / Admin@123') && dom.view.button('Reset sample data'), 'demo accounts and reset button are shown');
ok('built page boots, shows the login screen with demo accounts and reports standalone mode (no network used)');

inputs('text')[0].value = 'alice@example.com';
inputs('password')[0].value = 'wrong-password1';
await dom.view.byTag('form')[0].fire('submit');
await until(() => text().includes('Invalid credentials'), 'login error');
ok('wrong password is rejected with the generic message');

inputs('text')[0].value = 'alice@example.com';
inputs('password')[0].value = 'Student@123';
await dom.view.byTag('form')[0].fire('submit');
await until(() => dom.getHash() === '#/student/exams' && text().includes('SQL Basics Quiz'), 'student dashboard');
ok('student logs in with the sample account and sees both sample exams');

await dom.view.button('View instructions').click();
await until(() => dom.view.button('Start exam'), 'instructions');
await inputs('checkbox')[0].click();
await dom.view.button('Start exam').click();
await until(() => text().includes('Question 1 of 10'), 'exam screen');
const stored = () => JSON.parse(globalThis.localStorage.getItem('onlineExam.standalone.v1'));
const attempt = () => stored().attempts.find((a) => a.status === 'IN_PROGRESS');
const key = Object.fromEntries(stored().questions.map((q) => [q.id, q.correct]));
const order = attempt().questions.map((q) => q.qid);
for (let i = 0; i < 10; i++) {
  const pick = i < 8 ? key[order[i]] : (key[order[i]] === 'A' ? 'B' : 'A');          // 8 right, 2 wrong
  await inputs('radio').find((r) => r.value === pick).click();
  await until(() => attempt().answers[order[i]] === pick, 'answer saved ' + i);
  if (i < 9) await dom.view.button('Next').click();
}
ok('answers are saved into browser storage as they are chosen (original option letters)');

await dom.view.button('Submit exam').click();
await dom.overlays()[0].button('Submit').click();
await until(() => text().includes('PASSED'), 'result');
assert.ok(text().includes('Score: 8 / 10') && text().includes('Percentage: 80%'));
ok('submission is evaluated: 8 of 10, 80%, PASSED');

await dom.topbar.button('Log out').click();
await until(() => dom.getHash() === '#/login', 'logged out');
await dom.view.button('Administrator').click();
inputs('text')[0].value = 'admin';
inputs('password')[0].value = 'Admin@123';
await dom.view.byTag('form')[0].fire('submit');
await until(() => dom.getHash() === '#/admin/exams' && text().includes('Java Fundamentals Quiz'), 'admin dashboard');
globalThis.location.hash = '#/admin/attempts';
await until(() => text().includes('Alice Johnson') && text().includes('3 attempt(s)'), 'attempts list');
ok('administrator sees the new attempt in Attempts & results');

console.log('\nResult: ' + step + ' passed, 0 failed');
process.exit(0);
