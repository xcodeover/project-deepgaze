import { useEffect, useState } from 'react';

/**
 * Minimal pathname router. Avoids pulling in react-router for the ~2 routes
 * this app has today. API:
 *   const path = usePath()       // re-renders on pushState / popstate
 *   navigate('/settings/targets') // SPA-internal navigation (no reload)
 *
 * A MutationObserver-style synthetic `navigate` event is dispatched on
 * pushState so the hook picks up programmatic changes — popstate alone only
 * covers back/forward. Not exported because only the navigate() below fires
 * it, and routes wanting to listen use usePath().
 */
const NAV_EVENT = 'dg:navigate';

export function usePath() {
  const [path, setPath] = useState(() => window.location.pathname);
  useEffect(() => {
    const onChange = () => setPath(window.location.pathname);
    window.addEventListener('popstate', onChange);
    window.addEventListener(NAV_EVENT, onChange);
    return () => {
      window.removeEventListener('popstate', onChange);
      window.removeEventListener(NAV_EVENT, onChange);
    };
  }, []);
  return path;
}

export function navigate(to) {
  if (window.location.pathname === to) return;
  window.history.pushState({}, '', to);
  window.dispatchEvent(new Event(NAV_EVENT));
}
