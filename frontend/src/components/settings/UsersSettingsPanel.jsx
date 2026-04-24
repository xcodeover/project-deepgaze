import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  fetchUsers, createUser, deleteUser,
  resetUserPassword, setUserRole, setUserEnabled,
  changeMyPassword,
} from '../../api/users.js';
import { useAuthStore, selectAuthUser } from '../../store/authStore.js';
import PasswordInput from '../PasswordInput.jsx';

/**
 * Admin-only tab for managing signed-in users. Two sections:
 *
 *   1. Users table — list all accounts, toggle enabled, change role, reset
 *      password, delete. The backend refuses to delete/disable/demote the
 *      last enabled ADMIN and also refuses self-destructive actions, so
 *      "save failed" messages from those rails surface back through onAction.
 *
 *   2. My password — self-service change that anyone with a session can use.
 *      Lives on this tab because it's the natural place to put it once the
 *      admin view exists.
 *
 * The parent <SettingsShell> is responsible for only rendering this panel
 * when the signed-in user has role=ADMIN. We still guard against a
 * non-admin landing here via deep link: we fall back to the
 * self-service-only view if the list fetch 403s.
 */
const ROLES = ['ADMIN', 'OPERATOR', 'VIEWER'];
const PASSWORD_MIN = 8;

export default function UsersSettingsPanel() {
  const me = useAuthStore(selectAuthUser);
  const [users, setUsers] = useState([]);
  const [loaded, setLoaded] = useState(false);
  const [loadError, setLoadError] = useState(null);
  const [toast, setToast] = useState(null); // {kind:'ok'|'err', text}
  const [busyId, setBusyId] = useState(null); // row id currently mutating
  const [resetFor, setResetFor] = useState(null); // user being password-reset
  const [showCreate, setShowCreate] = useState(false);

  const refresh = useCallback(async () => {
    try {
      const list = await fetchUsers();
      setUsers(list);
      setLoadError(null);
    } catch (err) {
      setLoadError(err.message || String(err));
    } finally {
      setLoaded(true);
    }
  }, []);

  useEffect(() => { refresh(); }, [refresh]);

  const meUsername = (me?.username || '').toLowerCase();

  /* --- Mutations ------------------------------------------------------ */

  const onToggleEnabled = useCallback(async (u) => {
    setBusyId(u.id); setToast(null);
    try {
      await setUserEnabled(u.id, !u.enabled);
      await refresh();
      setToast({ kind: 'ok', text: `User '${u.username}' ${!u.enabled ? 'enabled' : 'disabled'}.` });
    } catch (err) {
      setToast({ kind: 'err', text: err.message || String(err) });
    } finally {
      setBusyId(null);
    }
  }, [refresh]);

  const onChangeRole = useCallback(async (u, role) => {
    if (role === u.role) return;
    setBusyId(u.id); setToast(null);
    try {
      await setUserRole(u.id, role);
      await refresh();
      setToast({ kind: 'ok', text: `Role updated for '${u.username}'.` });
    } catch (err) {
      setToast({ kind: 'err', text: err.message || String(err) });
      await refresh(); // reset stale select value on failure
    } finally {
      setBusyId(null);
    }
  }, [refresh]);

  const onDelete = useCallback(async (u) => {
    if (!window.confirm(`Delete user '${u.username}'? This cannot be undone.`)) return;
    setBusyId(u.id); setToast(null);
    try {
      await deleteUser(u.id);
      await refresh();
      setToast({ kind: 'ok', text: `User '${u.username}' deleted.` });
    } catch (err) {
      setToast({ kind: 'err', text: err.message || String(err) });
    } finally {
      setBusyId(null);
    }
  }, [refresh]);

  /* --- Render -------------------------------------------------------- */

  if (loadError && !loaded) {
    return <div className="settings__empty settings__empty--error">Failed to load users: {loadError}</div>;
  }

  // 403 after load means server says we're not admin. Render only the
  // self-service password change so non-admins still get a useful page.
  const canAdmin = !loadError && loaded;

  return (
    <>
      <div className="settings__toolbar">
        <div>
          <h2 className="settings__title">Users</h2>
          <div className="settings__sub">
            {canAdmin
              ? 'Create accounts, assign roles, rotate passwords. The system refuses to delete or disable the last enabled ADMIN.'
              : 'You do not have permission to manage other users. You can still change your own password below.'}
          </div>
        </div>
        {canAdmin && (
          <button
            type="button"
            className="tgt-btn tgt-btn--primary"
            onClick={() => setShowCreate((v) => !v)}
          >{showCreate ? 'Cancel' : '+ Add User'}</button>
        )}
      </div>

      {toast && (
        <div className={`settings__msg settings__msg--${toast.kind}`}>
          {toast.text}
          <button
            type="button"
            className="settings__msg-close"
            onClick={() => setToast(null)}
            aria-label="Dismiss"
          >×</button>
        </div>
      )}

      {canAdmin && showCreate && (
        <CreateUserForm
          onCancel={() => setShowCreate(false)}
          onCreated={async (created) => {
            setShowCreate(false);
            await refresh();
            setToast({ kind: 'ok', text: `User '${created.username}' created.` });
          }}
          onError={(msg) => setToast({ kind: 'err', text: msg })}
        />
      )}

      {canAdmin && (
        <div className="users-table-wrap">
          <table className="users-table">
            <thead>
              <tr>
                <th>Username</th>
                <th>Role</th>
                <th>Status</th>
                <th>Created</th>
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {users.length === 0 && (
                <tr><td colSpan={5} className="users-table__empty">No users yet.</td></tr>
              )}
              {users.map((u) => {
                const isMe = u.username.toLowerCase() === meUsername;
                const busy = busyId === u.id;
                return (
                  <tr key={u.id} className={!u.enabled ? 'users-table__row--off' : ''}>
                    <td>
                      <div className="users-table__uname">{u.username}</div>
                      {isMe && <div className="users-table__self">(you)</div>}
                    </td>
                    <td>
                      <select
                        className="users-table__role"
                        value={u.role}
                        disabled={busy || isMe}
                        onChange={(e) => onChangeRole(u, e.target.value)}
                        title={isMe ? 'You cannot change your own role' : 'Change role'}
                      >
                        {ROLES.map((r) => <option key={r} value={r}>{r}</option>)}
                      </select>
                    </td>
                    <td>
                      <label className="toggle toggle--sm">
                        <input
                          type="checkbox"
                          checked={u.enabled}
                          disabled={busy || isMe}
                          onChange={() => onToggleEnabled(u)}
                        />
                        <span className="toggle__slider" />
                        <span className="toggle__label">{u.enabled ? 'Enabled' : 'Disabled'}</span>
                      </label>
                    </td>
                    <td className="users-table__time">
                      {u.createdAt ? new Date(u.createdAt).toLocaleDateString() : '—'}
                    </td>
                    <td className="users-table__actions">
                      <button
                        type="button"
                        className="tgt-btn tgt-btn--ghost"
                        disabled={busy}
                        onClick={() => setResetFor(u)}
                      >Reset password</button>
                      <button
                        type="button"
                        className="tgt-btn tgt-btn--danger"
                        disabled={busy || isMe}
                        onClick={() => onDelete(u)}
                        title={isMe ? 'You cannot delete your own account' : 'Delete user'}
                      >Delete</button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {resetFor && (
        <ResetPasswordModal
          user={resetFor}
          onClose={() => setResetFor(null)}
          onDone={async (msg) => {
            setResetFor(null);
            await refresh();
            setToast({ kind: 'ok', text: msg });
          }}
          onError={(msg) => setToast({ kind: 'err', text: msg })}
        />
      )}

      <SelfPasswordChange
        onOk={(msg) => setToast({ kind: 'ok', text: msg })}
        onErr={(msg) => setToast({ kind: 'err', text: msg })}
      />
    </>
  );
}

/* ------------------------------------------------------------------ */

function CreateUserForm({ onCancel, onCreated, onError }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [role, setRole] = useState('VIEWER');
  const [submitting, setSubmitting] = useState(false);

  const submit = async (e) => {
    e.preventDefault();
    setSubmitting(true);
    try {
      const created = await createUser({ username: username.trim(), password, role });
      onCreated(created);
      setUsername(''); setPassword(''); setRole('VIEWER');
    } catch (err) {
      onError(err.message || String(err));
    } finally {
      setSubmitting(false);
    }
  };

  const canSubmit = username.trim().length > 0 && password.length >= PASSWORD_MIN;

  return (
    <form className="users-create" onSubmit={submit}>
      <div className="users-create__row">
        <label className="users-create__field">
          <span>Username</span>
          <input
            type="text"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            placeholder="slug — e.g. alice"
            autoComplete="off"
            spellCheck={false}
          />
        </label>
        <label className="users-create__field">
          <span>Password</span>
          <PasswordInput
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder={`at least ${PASSWORD_MIN} characters`}
            autoComplete="new-password"
          />
        </label>
        <label className="users-create__field users-create__field--narrow">
          <span>Role</span>
          <select value={role} onChange={(e) => setRole(e.target.value)}>
            {ROLES.map((r) => <option key={r} value={r}>{r}</option>)}
          </select>
        </label>
      </div>
      <div className="users-create__actions">
        <button type="button" className="tgt-btn tgt-btn--ghost" onClick={onCancel}>Cancel</button>
        <button
          type="submit"
          className="tgt-btn tgt-btn--primary"
          disabled={!canSubmit || submitting}
        >{submitting ? 'Creating…' : 'Create User'}</button>
      </div>
    </form>
  );
}

/* ------------------------------------------------------------------ */

function ResetPasswordModal({ user, onClose, onDone, onError }) {
  const [pwd, setPwd] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const submit = async (e) => {
    e.preventDefault();
    if (pwd.length < PASSWORD_MIN) return;
    setSubmitting(true);
    try {
      await resetUserPassword(user.id, pwd);
      onDone(`Password reset for '${user.username}'.`);
    } catch (err) {
      onError(err.message || String(err));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="users-modal__backdrop" role="dialog" aria-modal="true" onClick={onClose}>
      <form className="users-modal" onClick={(e) => e.stopPropagation()} onSubmit={submit}>
        <h3 className="users-modal__title">Reset password for {user.username}</h3>
        <p className="users-modal__body">
          The user will need to sign in again with the new password.
          Minimum {PASSWORD_MIN} characters.
        </p>
        <PasswordInput
          className="users-modal__input"
          value={pwd}
          onChange={(e) => setPwd(e.target.value)}
          placeholder="new password"
          autoComplete="new-password"
          autoFocus
        />
        <div className="users-modal__actions">
          <button type="button" className="tgt-btn tgt-btn--ghost" onClick={onClose}>Cancel</button>
          <button
            type="submit"
            className="tgt-btn tgt-btn--primary"
            disabled={pwd.length < PASSWORD_MIN || submitting}
          >{submitting ? 'Saving…' : 'Set Password'}</button>
        </div>
      </form>
    </div>
  );
}

/* ------------------------------------------------------------------ */

function SelfPasswordChange({ onOk, onErr }) {
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const canSubmit = useMemo(() => (
    current.length > 0 && next.length >= PASSWORD_MIN && next === confirm
  ), [current, next, confirm]);

  const submit = async (e) => {
    e.preventDefault();
    if (!canSubmit) return;
    setSubmitting(true);
    try {
      await changeMyPassword(current, next);
      setCurrent(''); setNext(''); setConfirm('');
      onOk('Your password has been updated.');
    } catch (err) {
      onErr(err.message || String(err));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <form className="users-self" onSubmit={submit}>
      <h3 className="users-self__title">Change My Password</h3>
      <div className="users-self__grid">
        <label className="users-create__field">
          <span>Current password</span>
          <PasswordInput
            value={current}
            onChange={(e) => setCurrent(e.target.value)}
            autoComplete="current-password"
          />
        </label>
        <label className="users-create__field">
          <span>New password</span>
          <PasswordInput
            value={next}
            onChange={(e) => setNext(e.target.value)}
            placeholder={`at least ${PASSWORD_MIN} characters`}
            autoComplete="new-password"
          />
        </label>
        <label className="users-create__field">
          <span>Confirm new password</span>
          <PasswordInput
            value={confirm}
            onChange={(e) => setConfirm(e.target.value)}
            autoComplete="new-password"
          />
        </label>
      </div>
      <div className="users-self__actions">
        <button
          type="submit"
          className="tgt-btn tgt-btn--primary"
          disabled={!canSubmit || submitting}
        >{submitting ? 'Saving…' : 'Update Password'}</button>
      </div>
    </form>
  );
}
