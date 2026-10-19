// Entry point of the single-file standalone build. Order matters: the in-browser backend is switched on first,
// because app.js starts talking to the API as soon as it is loaded.
import './local/enable.js';
import './app.js';
