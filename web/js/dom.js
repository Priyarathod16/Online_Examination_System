// Tiny DOM helpers. All text is inserted with textContent / text nodes, never innerHTML, so user-supplied
// strings (exam titles, question text, names) can never inject markup or script.

export function h(tag, props, ...children) {
  const el = document.createElement(tag);
  if (props) {
    for (const [key, value] of Object.entries(props)) {
      if (value === undefined || value === null || value === false) continue;
      if (key === 'class') el.className = value;
      else if (key === 'text') el.textContent = value;
      else if (key === 'style' && typeof value === 'object') Object.assign(el.style, value);
      else if (key.startsWith('on') && typeof value === 'function') el.addEventListener(key.slice(2).toLowerCase(), value);
      else if (key === 'value' || key === 'checked' || key === 'disabled' || key === 'selected') el[key] = value;
      else el.setAttribute(key, value === true ? '' : String(value));
    }
  }
  appendAll(el, children);
  return el;
}

function appendAll(el, children) {
  for (const child of children.flat(Infinity)) {
    if (child === null || child === undefined || child === false) continue;
    el.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
}

export function mount(container, ...nodes) {
  container.replaceChildren(...nodes.flat(Infinity).filter((n) => n !== null && n !== undefined && n !== false));
}

// ------------------------------------------------------------------------- formatting

export function fmtNum(value) {
  if (value === null || value === undefined) return '-';
  const n = Math.round(Number(value) * 100) / 100;
  return String(n);
}

export function fmtDate(iso) {
  if (!iso) return '-';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '-';
  return d.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
}

export function fmtScore(score, max) {
  return fmtNum(score) + ' / ' + fmtNum(max);
}

// -------------------------------------------------------------------------- components

export function badge(text, kind) {
  return h('span', { class: 'badge badge-' + (kind || 'neutral'), text });
}

export function resultBadge(status) {
  if (!status) return '-';
  return badge(status === 'PASS' ? 'Pass' : 'Fail', status === 'PASS' ? 'pass' : 'fail');
}

export function button(label, kind, onClick, extra) {
  return h('button', { type: 'button', class: 'btn btn-' + (kind || 'primary'), onClick, ...(extra || {}) }, label);
}

export function field(label, input, hint) {
  return h('label', { class: 'field' }, h('span', { class: 'field-label', text: label }), input,
    hint ? h('span', { class: 'field-hint', text: hint }) : null);
}

export function emptyState(text) {
  return h('p', { class: 'empty', text });
}

/**
 * Simple data table. columns: [{label, render(row, index) -> Node|string, class?}].
 * Returns the wrapper element; call table.update(rows) to replace the rows.
 */
export function dataTable(columns, rows, emptyText) {
  const wrap = h('div', { class: 'table-wrap' });
  function update(newRows) {
    const head = h('thead', null, h('tr', null, columns.map((c) => h('th', { text: c.label }))));
    const body = h('tbody', null, newRows.map((row, i) =>
      h('tr', null, columns.map((c) => h('td', { class: c.class }, c.render(row, i))))));
    mount(wrap, newRows.length === 0 ? emptyState(emptyText || 'Nothing to show yet.')
      : h('table', { class: 'table' }, head, body));
  }
  wrap.update = update;
  update(rows);
  return wrap;
}

export function toast(message, kind) {
  const host = document.getElementById('toasts');
  if (!host) return;
  const el = h('div', { class: 'toast toast-' + (kind || 'info'), role: 'status', text: message });
  host.append(el);
  setTimeout(() => el.remove(), kind === 'error' ? 7000 : 4000);
}

/**
 * Modal dialog. actions: [{label, kind, onClick}] - onClick may return false (or a promise resolving to false)
 * to keep the dialog open. Returns {close, body}.
 */
export function openModal({ title, body, actions, wide }) {
  const previouslyFocused = document.activeElement;
  const overlay = h('div', { class: 'overlay' });
  const dialog = h('div', { class: 'dialog' + (wide ? ' dialog-wide' : ''), role: 'dialog', 'aria-modal': 'true', 'aria-label': title });
  const footer = h('div', { class: 'dialog-actions' });
  function close() {
    document.removeEventListener('keydown', onKey);
    overlay.remove();
    if (previouslyFocused && previouslyFocused.focus) previouslyFocused.focus();
  }
  function onKey(e) {
    if (e.key === 'Escape') close();
  }
  for (const a of actions || []) {
    const btn = h('button', { type: 'button', class: 'btn btn-' + (a.kind || 'secondary'), text: a.label });
    btn.addEventListener('click', async () => {
      btn.disabled = true;
      let keep = false;
      try {
        keep = a.onClick ? (await a.onClick()) === false : false;
      } finally {
        btn.disabled = false;
      }
      if (!keep) close();
    });
    footer.append(btn);
  }
  dialog.append(h('h2', { class: 'dialog-title', text: title }), h('div', { class: 'dialog-body' }, body), footer);
  overlay.append(dialog);
  document.body.append(overlay);
  document.addEventListener('keydown', onKey);
  return { close, body: dialog };
}

/** Resolves true when the user confirms, false otherwise. */
export function confirmDialog(message, { okLabel = 'OK', danger = false, title = 'Please confirm' } = {}) {
  return new Promise((resolve) => {
    const lines = String(message).split('\n').map((line) => h('p', { text: line }));
    openModal({
      title,
      body: lines,
      actions: [
        { label: 'Cancel', kind: 'secondary', onClick: () => resolve(false) },
        { label: okLabel, kind: danger ? 'danger' : 'primary', onClick: () => resolve(true) },
      ],
    });
  });
}
