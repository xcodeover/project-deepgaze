import { create } from 'zustand';

/**
 * Session store. Owns the JWT and the small user descriptor that lets the UI
 * render a "signed in as …" chip without hitting /api/auth/me on every render.
 *
 * Persistence:
 *   token + user survive page reloads via localStorage. We read once at
 *   module init (not lazily) so the very first apiFetch() on a cold reload
 *   already carries the Bearer header — otherwise the initial /api/targets
 *   would race the rehydration and 401.
 *
 *   status is NOT persisted: it's always derived from whether we currently
 *   hold a token, and resets to 'anonymous' / 'authenticated' on boot.
 *
 * No 'loading' state on boot because we don't pre-validate the token with
 * /api/auth/me — the next real API call will either succeed or emit 401,
 * and the 401 path in api/http.js drops the session + redirects. Keeps the
 * dashboard rendering instantly on reload.
 */
const TOKEN_KEY = 'deepgaze.auth.token';
const USER_KEY  = 'deepgaze.auth.user';

function readPersisted() {
  try {
    const token = localStorage.getItem(TOKEN_KEY) || null;
    const raw   = localStorage.getItem(USER_KEY);
    const user  = raw ? JSON.parse(raw) : null;
    return { token, user };
  } catch {
    return { token: null, user: null };
  }
}

function writePersisted(token, user) {
  try {
    if (token) localStorage.setItem(TOKEN_KEY, token);
    else       localStorage.removeItem(TOKEN_KEY);
    if (user)  localStorage.setItem(USER_KEY, JSON.stringify(user));
    else       localStorage.removeItem(USER_KEY);
  } catch { /* storage disabled — session is effectively per-tab */ }
}

const initial = readPersisted();

export const useAuthStore = create((set, get) => ({
  token:  initial.token,
  user:   initial.user,
  status: initial.token ? 'authenticated' : 'anonymous',
  error:  null,

  /**
   * Synchronous setter used by both the Login form (on success) and the
   * apiFetch 401 handler (on session loss). The store is the single write
   * point — persistence + React re-render happen together.
   */
  setSession: (token, user) => {
    writePersisted(token, user);
    set({
      token, user,
      status: token ? 'authenticated' : 'anonymous',
      error: null,
    });
  },

  clearSession: () => {
    writePersisted(null, null);
    set({ token: null, user: null, status: 'anonymous', error: null });
  },

  /**
   * POST /api/auth/login. On 2xx we persist the token and flip to
   * authenticated. On non-2xx we surface the server's error message so the
   * Login form can render it under the password field.
   */
  login: async (username, password) => {
    set({ status: 'signing-in', error: null });
    try {
      const res = await fetch('/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password }),
      });
      const body = await res.json().catch(() => ({}));
      if (!res.ok) {
        const msg = body?.error || `Login failed (HTTP ${res.status})`;
        set({ status: 'anonymous', error: msg });
        return { ok: false, error: msg };
      }
      get().setSession(body.token, body.user);
      return { ok: true };
    } catch (err) {
      const msg = `Network error: ${err.message || err}`;
      set({ status: 'anonymous', error: msg });
      return { ok: false, error: msg };
    }
  },

  logout: () => get().clearSession(),
}));

/**
 * Non-reactive token read used by api/http.js and the SSE query-string
 * builder — those call sites live outside React and must not subscribe to
 * the store (no re-render budget, no hook rules).
 */
export function getToken() {
  return useAuthStore.getState().token;
}

export const selectAuthStatus = (s) => s.status;
export const selectAuthUser   = (s) => s.user;
export const selectAuthError  = (s) => s.error;
