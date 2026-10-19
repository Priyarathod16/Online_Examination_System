// A very small DOM + browser-globals fake, just enough to run the website's view code under Node without a browser.
// It is a test aid only (not shipped to users).

export class FakeNode {
  constructor() { this.parent = null; this.childNodes = []; }
}

export class FakeText extends FakeNode {
  constructor(text) { super(); this.data = String(text); }
  get textContent() { return this.data; }
}

export class FakeElement extends FakeNode {
  constructor(tag) {
    super();
    this.tagName = tag.toUpperCase();
    this.attrs = {};
    this.listeners = {};
    this.style = {};
    this.className = '';
    this.value = '';
    this.checked = false;
    this.disabled = false;
    this.hidden = false;
  }

  append(...nodes) {
    for (const n of nodes) {
      const node = typeof n === 'string' ? new FakeText(n) : n;
      node.parent = this;
      this.childNodes.push(node);
    }
  }

  replaceChildren(...nodes) {
    this.childNodes = [];
    this.append(...nodes);
  }

  remove() {
    if (this.parent) this.parent.childNodes = this.parent.childNodes.filter((n) => n !== this);
    this.parent = null;
  }

  setAttribute(k, v) { this.attrs[k] = String(v); if (k === 'class') this.className = String(v); }
  getAttribute(k) { return this.attrs[k] ?? null; }
  addEventListener(type, fn) { (this.listeners[type] ||= []).push(fn); }
  removeEventListener(type, fn) { this.listeners[type] = (this.listeners[type] || []).filter((f) => f !== fn); }
  focus() { globalThis.document.activeElement = this; }

  get textContent() { return this.childNodes.map((c) => c.textContent).join(''); }
  set textContent(v) { this.childNodes = []; if (v !== '' && v !== null && v !== undefined) this.append(String(v)); }

  /**
   * Calls every listener. Like a browser, it does not wait for a handler that is blocked (for example on a dialog the
   * user has not answered yet): it waits until the handlers settle or a few event-loop turns have passed.
   */
  async fire(type, extra = {}) {
    const event = { type, target: this, defaultPrevented: false, preventDefault() { this.defaultPrevented = true; }, ...extra };
    const pending = [...(this.listeners[type] || [])].map((fn) => fn(event));
    await Promise.race([Promise.all(pending), flush()]);
    return event;
  }

  /** Simulates a user click (toggles checkboxes, selects radios, fires change). */
  async click() {
    if (this.disabled) return;
    if (this.tagName === 'INPUT' && this.attrs.type === 'checkbox') {
      this.checked = !this.checked;
      await this.fire('click');
      await this.fire('change');
      return;
    }
    if (this.tagName === 'INPUT' && this.attrs.type === 'radio') {
      this.checked = true;
      await this.fire('click');
      await this.fire('change');
      return;
    }
    await this.fire('click');
  }

  async type(value) { this.value = value; await this.fire('input'); }

  findAll(predicate, out = []) {
    for (const c of this.childNodes) {
      if (c instanceof FakeElement) {
        if (predicate(c)) out.push(c);
        c.findAll(predicate, out);
      }
    }
    return out;
  }

  find(predicate) { return this.findAll(predicate)[0] || null; }
  byTag(tag) { return this.findAll((e) => e.tagName === tag.toUpperCase()); }
  hasClass(name) { return this.className.split(/\s+/).includes(name); }
  /** Button/link whose visible text equals `text`. */
  button(text) { return this.find((e) => (e.tagName === 'BUTTON' || e.tagName === 'A') && e.textContent === text); }
}

/** Manual timers so tests control the passage of time. */
export function installTimers() {
  const timers = { now: 0, nextId: 1, list: new Map() };
  globalThis.setTimeout = (fn, ms) => { const id = timers.nextId++; timers.list.set(id, { fn, at: timers.now + ms, every: 0 }); return id; };
  globalThis.setInterval = (fn, ms) => { const id = timers.nextId++; timers.list.set(id, { fn, at: timers.now + ms, every: ms }); return id; };
  globalThis.clearTimeout = (id) => { timers.list.delete(id); };
  globalThis.clearInterval = (id) => { timers.list.delete(id); };
  Object.defineProperty(globalThis, 'performance', { value: { now: () => timers.now }, configurable: true, writable: true });
  timers.advance = async (ms) => {
    const end = timers.now + ms;
    for (;;) {
      let nextId = null;
      let next = null;
      for (const [id, t] of timers.list) if (t.at <= end && (next === null || t.at < next.at)) { next = t; nextId = id; }
      if (next === null) break;
      timers.now = next.at;
      if (next.every) next.at += next.every; else timers.list.delete(nextId);
      next.fn();
      await flush();
    }
    timers.now = end;
    await flush();
  };
  return timers;
}

export async function flush() {
  for (let i = 0; i < 6; i++) await new Promise((r) => setImmediate(r));
}

export function installDom() {
  const listeners = {};
  const body = new FakeElement('body');
  const ids = {};
  globalThis.Node = FakeNode;
  globalThis.document = {
    body,
    activeElement: null,
    createElement: (tag) => new FakeElement(tag),
    createTextNode: (t) => new FakeText(t),
    getElementById: (id) => ids[id] || null,
    addEventListener: (t, fn) => { (listeners[t] ||= []).push(fn); },
    removeEventListener: (t, fn) => { listeners[t] = (listeners[t] || []).filter((f) => f !== fn); },
  };
  const winListeners = {};
  globalThis.window = {
    addEventListener: (t, fn) => { (winListeners[t] ||= []).push(fn); },
    scrollTo: () => {},
  };
  let hash = '';
  globalThis.location = {
    get hash() { return hash; },
    set hash(v) {
      const next = v.startsWith('#') ? v : '#' + v;
      if (next === hash) return;
      hash = next;
      queueMicrotask(() => (winListeners.hashchange || []).forEach((fn) => fn({ type: 'hashchange' })));
    },
  };
  const register = (id) => { const el = new FakeElement('div'); ids[id] = el; body.append(el); return el; };
  return {
    body,
    view: register('view'),
    topbar: register('topbar'),
    toasts: register('toasts'),
    keydown: (key) => (listeners.keydown || []).forEach((fn) => fn({ key })),
    setHash: (h) => { hash = h; },
    getHash: () => hash,
    overlays: () => body.childNodes.filter((n) => n instanceof FakeElement && n.hasClass('overlay')),
  };
}

/** Mock for fetch(): routes is a function (method, path, body) -> {status?, json?} | Promise of the same. */
export function installFetch(handler) {
  const calls = [];
  globalThis.fetch = async (url, options = {}) => {
    const call = { method: options.method || 'GET', path: String(url).replace(/^\/api/, ''), body: options.body ? JSON.parse(options.body) : undefined, headers: options.headers || {} };
    calls.push(call);
    const result = (await handler(call.method, call.path, call.body, call)) || {};
    const status = result.status || 200;
    const text = result.json === undefined ? '' : JSON.stringify(result.json);
    return { ok: status >= 200 && status < 300, status, text: async () => text };
  };
  return calls;
}
