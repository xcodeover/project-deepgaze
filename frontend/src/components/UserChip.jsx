import { useAuthStore, selectAuthUser } from '../store/authStore.js';
import { navigate } from '../lib/router.js';

/**
 * Signed-in-as chip for the app header. Clicking Sign out clears the
 * session synchronously (so the next render sees anonymous state) and
 * navigates to /login — AuthGuard would catch a missed redirect, but
 * doing it explicitly avoids a flash of the protected layout.
 */
export default function UserChip() {
  const user   = useAuthStore(selectAuthUser);
  const logout = useAuthStore((s) => s.logout);
  if (!user) return null;

  const onSignOut = () => {
    logout();
    navigate('/login');
  };

  return (
    <div className="user-chip" role="group" aria-label="Signed-in user">
      <div className="user-chip__avatar" aria-hidden="true">
        {(user.username || '?').slice(0, 1).toUpperCase()}
      </div>
      <div className="user-chip__meta">
        <div className="user-chip__name">{user.username}</div>
        <div className="user-chip__role">{user.role || 'USER'}</div>
      </div>
      <button
        type="button"
        className="user-chip__logout"
        onClick={onSignOut}
        title="Sign out"
      >Sign out</button>
    </div>
  );
}
