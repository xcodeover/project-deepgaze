import { useEffect } from 'react';
import { navigate, usePath } from '../../lib/router.js';
import UserChip from '../UserChip.jsx';
import { useAuthStore, selectAuthUser } from '../../store/authStore.js';
import TargetSettingsPanel from './TargetSettingsPanel.jsx';
import NotificationSettingsPanel from './NotificationSettingsPanel.jsx';
import HistorySettingsPanel from './HistorySettingsPanel.jsx';
import UsersSettingsPanel from './UsersSettingsPanel.jsx';

/**
 * Tabbed admin panel reachable at /settings/**. The shell owns the header
 * and the tab bar; each tab panel focuses on a single resource
 * (database targets, notification channels, user accounts). Deep links
 * resolve directly to a tab so operators can bookmark any page.
 *
 * Routes handled here (the parent App routes any /settings prefix to this
 * component):
 *   /settings                    → redirects to /settings/databases
 *   /settings/databases          → TargetSettingsPanel
 *   /settings/targets            → alias of /databases (backcompat)
 *   /settings/notifications      → NotificationSettingsPanel
 *   /settings/users              → UsersSettingsPanel (ADMIN tab;
 *                                  non-admins still land here for the
 *                                  self-service password change section)
 */
const BASE_TABS = [
  { id: 'databases',     label: 'Databases',     path: '/settings/databases' },
  { id: 'notifications', label: 'Notifications', path: '/settings/notifications' },
  { id: 'history',       label: 'Time Machine',  path: '/settings/history' },
];
const USERS_TAB = { id: 'users', label: 'Users', path: '/settings/users' };

export default function SettingsShell() {
  const path = usePath();
  const user = useAuthStore(selectAuthUser);
  const isAdmin = (user?.role || '').toUpperCase() === 'ADMIN';
  const tabs = isAdmin ? [...BASE_TABS, USERS_TAB] : BASE_TABS;
  const active = resolveActiveTab(path, isAdmin);

  // Canonicalise /settings (no tab) and legacy /settings/targets onto the
  // databases route so the URL always reflects the visible tab. A
  // non-admin deep-linking to /settings/users is redirected away — the
  // tab is hidden for them and the panel would only render the
  // self-service form, which is reachable elsewhere.
  useEffect(() => {
    if (path === '/settings' || path === '/settings/') {
      navigate('/settings/databases');
    } else if (path.startsWith('/settings/targets')) {
      navigate('/settings/databases');
    } else if (!isAdmin && path.startsWith('/settings/users')) {
      navigate('/settings/databases');
    }
  }, [path, isAdmin]);

  return (
    <div className="app">
      <header className="app__header">
        <div className="app__title">
          DeepGaze
          <small>Admin · Settings</small>
        </div>
        <div className="app__header-right">
          <button
            type="button"
            className="tgt-btn tgt-btn--ghost"
            onClick={() => navigate('/')}
          >← Back to Fleet</button>
          <UserChip />
        </div>
      </header>

      <div className="settings">
        <nav className="settings__tabs" role="tablist" aria-label="Settings sections">
          {tabs.map((t) => (
            <button
              key={t.id}
              type="button"
              role="tab"
              aria-selected={active === t.id}
              className={`settings__tab ${active === t.id ? 'settings__tab--active' : ''}`}
              onClick={() => navigate(t.path)}
            >{t.label}</button>
          ))}
        </nav>

        <div className="settings__panel" role="tabpanel">
          {active === 'notifications' && <NotificationSettingsPanel />}
          {active === 'history'       && <HistorySettingsPanel />}
          {active === 'users'         && <UsersSettingsPanel />}
          {active === 'databases'     && <TargetSettingsPanel />}
        </div>
      </div>
    </div>
  );
}

function resolveActiveTab(path, isAdmin) {
  if (path.startsWith('/settings/notifications')) return 'notifications';
  if (path.startsWith('/settings/history'))       return 'history';
  if (isAdmin && path.startsWith('/settings/users')) return 'users';
  return 'databases';
}
