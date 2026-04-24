import { useEffect, useMemo } from 'react';
import Dashboard from './components/Dashboard.jsx';
import SettingsShell from './components/settings/SettingsShell.jsx';
import FleetDashboard from './components/fleet/FleetDashboard.jsx';
import Login from './components/Login.jsx';
import AuthGuard from './components/AuthGuard.jsx';
import { usePath } from './lib/router.js';
import { useAuthStore, selectAuthStatus } from './store/authStore.js';
import { useTargetsStore } from './store/targetsStore.js';
import { useMetricStream } from './hooks/useMetricStream.js';
import { useAlertStream } from './hooks/useAlertStream.js';

/**
 * App-level shell:
 *   - /login renders bare (no guard, no streams) so the login screen can't
 *     recursively demand auth.
 *   - Every other route is wrapped in <AuthGuard>. When anonymous, the
 *     guard redirects to /login and AppInner never mounts — which means
 *     useMetricStream / useAlertStream / loadTargets never fire while
 *     logged out. That keeps SSE from banging on the backend with a token
 *     it doesn't have, and keeps the targets list out of localStorage-
 *     level caches between users.
 *   - Routes inside AppInner:
 *       /                 → FleetDashboard
 *       /targets/:id      → Dashboard
 *       /settings[/*]     → SettingsShell
 *     Unknown paths fall through to FleetDashboard.
 */
export default function App() {
  const path = usePath();
  const authStatus = useAuthStore(selectAuthStatus);

  if (path === '/login' || path.startsWith('/login?')) return <Login />;

  return (
    <AuthGuard>
      <AppInner path={path} key={authStatus === 'authenticated' ? 'signed-in' : 'anon'} />
    </AuthGuard>
  );
}

function AppInner({ path }) {
  const loadTargets = useTargetsStore((s) => s.load);

  useMetricStream();
  useAlertStream();

  useEffect(() => {
    loadTargets();
  }, [loadTargets]);

  const route = useMemo(() => parseRoute(path), [path]);

  if (route.name === 'settings') return <SettingsShell />;
  if (route.name === 'target')   return <Dashboard targetId={route.targetId} />;
  return <FleetDashboard />;
}

function parseRoute(path) {
  if (path.startsWith('/settings')) return { name: 'settings' };
  const m = path.match(/^\/targets\/([^/?#]+)/);
  if (m) return { name: 'target', targetId: decodeURIComponent(m[1]) };
  return { name: 'fleet' };
}
