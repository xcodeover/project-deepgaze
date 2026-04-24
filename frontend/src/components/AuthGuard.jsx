import { useEffect } from 'react';
import { useAuthStore, selectAuthStatus } from '../store/authStore.js';
import { navigate } from '../lib/router.js';

/**
 * Gate wrapper for every protected screen. If the current status isn't
 * 'authenticated' we redirect to /login and preserve the attempted URL in
 * ?next= so the login screen can bounce back after success.
 *
 * We run the check in an effect (not during render) because calling
 * navigate() during render would push history in the same tick as the
 * first paint and fight with React's scheduling. A single null return
 * while the effect fires is fine — the next render will be /login.
 */
export default function AuthGuard({ children }) {
  const status = useAuthStore(selectAuthStatus);
  const authed = status === 'authenticated';

  useEffect(() => {
    if (authed) return;
    const here = window.location.pathname + window.location.search;
    const next = here && here !== '/login' ? `?next=${encodeURIComponent(here)}` : '';
    navigate(`/login${next}`);
  }, [authed]);

  return authed ? children : null;
}
