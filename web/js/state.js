// Shared application state and navigation helpers.

export const state = {
  user: null,          // {role: 'student' | 'admin', name}
  rerender: () => {},  // set by app.js
};

export function go(hash) {
  if (location.hash === hash) state.rerender();
  else location.hash = hash;
}
