// Side-effect module: routes all API calls to the in-browser backend. Must be imported BEFORE app.js.
import { useLocalBackend } from '../api.js';
import { localApi } from './local-backend.js';

useLocalBackend(localApi);
