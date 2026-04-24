import { useEffect, useRef, useState } from 'react';
import {
  useAuthStore,
  selectAuthStatus,
  selectAuthError,
} from '../store/authStore.js';
import { navigate } from '../lib/router.js';
import PasswordInput from './PasswordInput.jsx';

/**
 * Dark-themed login card. One screen, one form, no distractions.
 *
 * Redirects away from /login the moment we become authenticated — covers
 * both "user just submitted credentials" and "user hit /login while
 * already holding a valid token in localStorage" (e.g. bookmarked link).
 */
export default function Login() {
  const login   = useAuthStore((s) => s.login);
  const status  = useAuthStore(selectAuthStatus);
  const error   = useAuthStore(selectAuthError);

  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const usernameRef = useRef(null);

  useEffect(() => { usernameRef.current?.focus(); }, []);

  useEffect(() => {
    if (status === 'authenticated') {
      const params = new URLSearchParams(window.location.search);
      const next = params.get('next') || '/';
      navigate(safeNext(next));
    }
  }, [status]);

  const submit = async (e) => {
    e.preventDefault();
    if (!username.trim() || !password) return;
    await login(username.trim(), password);
  };

  const busy = status === 'signing-in';

  return (
    <div className="login-shell">
      <div className="login-card" role="main" aria-labelledby="login-title">
        <div className="login-card__brand">
          <div className="login-card__logo">◆</div>
          <div>
            <div className="login-card__title" id="login-title">DeepGaze</div>
            <div className="login-card__subtitle">Agentless DB Monitoring</div>
          </div>
        </div>

        <form className="login-form" onSubmit={submit} noValidate>
          <label className="login-field">
            <span className="login-field__label">Username</span>
            <input
              ref={usernameRef}
              className="login-field__input"
              type="text"
              autoComplete="username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              disabled={busy}
              required
            />
          </label>

          <label className="login-field">
            <span className="login-field__label">Password</span>
            <PasswordInput
              className="login-field__input"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              disabled={busy}
              required
            />
          </label>

          {error && (
            <div className="login-error" role="alert">{error}</div>
          )}

          <button
            type="submit"
            className="login-submit"
            disabled={busy || !username.trim() || !password}
          >
            {busy ? 'Signing in…' : 'Sign in'}
          </button>
        </form>

        <div className="login-card__footer">
          Stateless JWT · session expires per backend TTL
        </div>
      </div>
    </div>
  );
}

/**
 * Sanity-check the ?next= param so a crafted link can't bounce us to an
 * absolute or protocol-relative URL after login.
 */
function safeNext(next) {
  if (typeof next !== 'string' || !next.startsWith('/')) return '/';
  if (next.startsWith('//'))   return '/';
  if (next.startsWith('/login')) return '/';
  return next;
}
