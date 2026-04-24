import { useAuthStore, getToken } from '../store/authStore.js';
import { navigate } from '../lib/router.js';

/**
 * Shared fetch wrapper. Every api/*.js module routes through this so the
 * JWT is injected once and session-loss behaviour lives in one place.
 *
 * Responsibilities:
 *   - Attach `Authorization: Bearer <token>` when we hold one.
 *   - On a 401 response: drop the cached session and bounce to /login
 *     unless we're already there. The caller still gets the rejected
 *     promise so any in-flight UI (settings save, kill modal) can unwind.
 *   - Preserve the native fetch surface — callers can `await apiFetch(...)`
 *     exactly like fetch.
 *
 * We deliberately do NOT throw on non-2xx here. Each api/*.js module has
 * its own error-surface preferences (some throw, some return typed bodies);
 * shoving a single policy into the wrapper would break those callers.
 *
 * The /api/auth/login POST itself does NOT go through here — it must work
 * while anonymous, and a 401 from it means "bad credentials" not "session
 * expired". authStore.login uses raw fetch.
 */
export async function apiFetch(url, opts = {}) {
  const token   = getToken();
  const headers = new Headers(opts.headers || {});
  if (token && !headers.has('Authorization')) {
    headers.set('Authorization', `Bearer ${token}`);
  }
  const res = await fetch(url, { ...opts, headers });
  if (res.status === 401) handleSessionLost();
  return res;
}

/**
 * JSON-parsed variant. Throws on non-2xx with the server body as the
 * message (falls back to `HTTP <code>`). Matches the `request()` helper
 * that api/targets.js and api/notifications.js already use, so the
 * retrofit is a one-line swap.
 */
export async function apiJson(url, opts = {}) {
  const res = await apiFetch(url, opts);
  if (!res.ok) {
    let msg = `HTTP ${res.status}`;
    try {
      const body = await res.text();
      if (body) msg = body;
    } catch { /* no body */ }
    throw new Error(msg);
  }
  if (res.status === 204) return null;
  return res.json();
}

/**
 * Append `?token=<jwt>` (or `&token=…` if the URL already has a query
 * string) for EventSource endpoints. The browser's native EventSource
 * API ignores any Authorization header we try to set from JS, so the
 * only way to authenticate SSE is via the URL.
 *
 * Returns the URL unchanged when we don't hold a token — the backend
 * will 401 on connect, which bubbles back to AuthGuard through the
 * normal REST path (the store's bootstrap() call) and triggers the
 * login redirect.
 */
export function withAuthQuery(url) {
  const token = getToken();
  if (!token) return url;
  const sep = url.includes('?') ? '&' : '?';
  return `${url}${sep}token=${encodeURIComponent(token)}`;
}

function handleSessionLost() {
  const state = useAuthStore.getState();
  if (!state.token) return; // already anonymous — nothing to unwind
  state.clearSession();
  if (typeof window !== 'undefined' && window.location.pathname !== '/login') {
    navigate('/login');
  }
}
