// Thin wrapper around fetch() for the JSON API. Every error becomes an ApiError with a user-safe message.

export class ApiError extends Error {
  constructor(message, status, code) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }
}

let unauthorizedHandler = null;
let localHandler = null;

/**
 * Standalone mode: route every API call to an in-browser implementation instead of the network
 * (see js/local/local-backend.js). Not used when the site is served by the Java backend.
 */
export function useLocalBackend(handler) {
  localHandler = handler;
}

/** Called when the server says the session is missing or expired (HTTP 401 on a non-login request). */
export function onUnauthorized(fn) {
  unauthorizedHandler = fn;
}

export async function api(method, path, body) {
  if (localHandler) {
    try {
      return await localHandler(method, path, body === undefined ? undefined : JSON.parse(JSON.stringify(body)));
    } catch (e) {
      if (!(e instanceof ApiError)) {
        console.error(e);
        throw new ApiError('Something unexpected went wrong. Please try again.', 500, 'server');
      }
      if (e.status === 401 && path !== '/login' && unauthorizedHandler) unauthorizedHandler(e);
      throw e;
    }
  }
  const options = {
    method,
    credentials: 'same-origin',
    headers: { 'X-Requested-With': 'XMLHttpRequest' },
  };
  if (body !== undefined) {
    options.headers['Content-Type'] = 'application/json';
    options.body = JSON.stringify(body);
  }
  let response;
  try {
    response = await fetch('/api' + path, options);
  } catch (e) {
    throw new ApiError('Cannot reach the server. Please check your connection and try again.', 0, 'network');
  }
  let data = null;
  const text = await response.text();
  if (text) {
    try {
      data = JSON.parse(text);
    } catch (e) {
      data = null;
    }
  }
  if (!response.ok) {
    const message = (data && data.error) || 'The request failed (' + response.status + ').';
    const err = new ApiError(message, response.status, data && data.code);
    if (response.status === 401 && path !== '/login' && unauthorizedHandler) unauthorizedHandler(err);
    throw err;
  }
  return data;
}

export const get = (path) => api('GET', path);
export const post = (path, body) => api('POST', path, body === undefined ? {} : body);
export const put = (path, body) => api('PUT', path, body);
export const del = (path) => api('DELETE', path);
