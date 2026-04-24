import { useEffect, useRef, useState } from 'react';
import { useTargetsStore } from '../store/targetsStore.js';
import PasswordInput from './PasswordInput.jsx';

const ENGINES = [
  { id: 'MARIADB', label: 'MariaDB / MySQL' },
  { id: 'ORACLE',  label: 'Oracle' },
  { id: 'MSSQL',   label: 'Microsoft SQL Server' },
];

const PASSWORD_PLACEHOLDER = '••••••••••';

/**
 * Add / Edit modal for a single target.
 *
 * Password UX (the crucial bit):
 *   - In "edit" mode with the server reporting `passwordSet: true`, the
 *     input renders empty with `••••••••••` as the placeholder.
 *   - We track a separate `passwordDirty` flag per field (password / ops).
 *   - On save, we ONLY include the password field in the PUT payload when
 *     the operator actually typed something. That way clicking "Save" on
 *     a rename doesn't blast the stored password away with an empty string.
 *
 * Test Connection builds an in-memory DTO (same shape the backend accepts
 * for probing) and hits /api/targets/test-connection. The result renders
 * inline above the action row — operators see a green "connected in 87 ms"
 * or a red error message BEFORE committing the config.
 */
export default function TargetEditModal({ target, onClose, onSaved }) {
  const createAction = useTargetsStore((s) => s.create);
  const updateAction = useTargetsStore((s) => s.update);
  const testAction   = useTargetsStore((s) => s.testConnection);

  const isNew = !target;
  const [form, setForm] = useState(() => seed(target));
  const [passwordDirty, setPasswordDirty]       = useState(false);
  const [opsPasswordDirty, setOpsPasswordDirty] = useState(false);
  const [testing, setTesting]   = useState(false);
  const [testResult, setTestResult] = useState(null);
  const [saving, setSaving]     = useState(false);
  const [error, setError]       = useState(null);

  const firstFieldRef = useRef(null);
  useEffect(() => { firstFieldRef.current?.focus(); }, []);

  const setField = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }));

  const onPwChange = (e) => {
    setPasswordDirty(true);
    setForm((f) => ({ ...f, password: e.target.value }));
  };
  const onOpsPwChange = (e) => {
    setOpsPasswordDirty(true);
    setForm((f) => ({ ...f, opsPassword: e.target.value }));
  };

  const buildPayload = (forProbe = false) => {
    // Normalise numeric fields; empty strings become undefined so the backend
    // uses its defaults for optional numerics instead of rejecting "".
    const p = {
      id:              form.id.trim(),
      displayName:     form.displayName.trim(),
      engine:          form.engine,
      jdbcUrl:         form.jdbcUrl.trim(),
      username:        form.username.trim(),
      pollIntervalMs:  numOrNull(form.pollIntervalMs),
      hostExporterUrl: form.hostExporterUrl.trim() || null,
      opsUsername:     form.opsUsername.trim() || null,
      hikariMaxPoolSize:   numOrNull(form.hikariMaxPoolSize),
      hikariMinimumIdle:   numOrNull(form.hikariMinimumIdle),
      tcpConnectTimeoutMs: numOrNull(form.tcpConnectTimeoutMs),
      socketReadTimeoutMs: numOrNull(form.socketReadTimeoutMs),
    };

    // On create, an empty password field is a user error — we still send it
    // so the backend reports "password required" cleanly. On edit, we only
    // overwrite when the operator typed something new.
    if (forProbe) {
      p.password = form.password || '';
      if (form.opsUsername) p.opsPassword = form.opsPassword || '';
    } else if (isNew) {
      p.password = form.password || '';
      if (form.opsUsername) p.opsPassword = form.opsPassword || '';
    } else {
      if (passwordDirty)    p.password    = form.password;
      if (opsPasswordDirty) p.opsPassword = form.opsPassword;
    }

    return p;
  };

  const handleTest = async () => {
    setError(null);
    setTestResult(null);
    setTesting(true);
    try {
      const probe = buildPayload(true);
      const result = await testAction(probe);
      setTestResult(result);
    } catch (err) {
      setTestResult({ ok: false, message: err.message || String(err) });
    } finally {
      setTesting(false);
    }
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError(null);
    setSaving(true);
    try {
      const payload = buildPayload(false);
      let saved;
      if (isNew) {
        saved = await createAction(payload);
        onSaved?.(saved, 'create');
      } else {
        saved = await updateAction(target.id, payload);
        onSaved?.(saved, 'update');
      }
    } catch (err) {
      setError(err.message || String(err));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="modal-overlay" onMouseDown={onClose}>
      <div
        className="modal modal--target"
        role="dialog"
        aria-modal="true"
        aria-label={isNew ? 'Add target' : `Edit ${target.displayName}`}
        onMouseDown={(e) => e.stopPropagation()}
      >
        <div className="modal__header">
          <div>
            <div className="modal__title">{isNew ? 'Add Target' : `Edit: ${target.displayName}`}</div>
            <div className="modal__sub">{isNew ? 'New database target' : target.id}</div>
          </div>
          <button type="button" className="modal__btn modal__btn--ghost" onClick={onClose}>Close</button>
        </div>

        <form className="modal__body" onSubmit={handleSubmit}>
          <div className="tgt-form__grid">
            <Field label="Target ID" hint="lowercase, digits, dashes/underscores; immutable after save">
              <input
                ref={firstFieldRef}
                className="modal__input"
                value={form.id}
                onChange={setField('id')}
                disabled={!isNew}
                required
                placeholder="stocktrader-db"
              />
            </Field>

            <Field label="Display Name">
              <input className="modal__input" value={form.displayName} onChange={setField('displayName')} required />
            </Field>

            <Field label="Engine">
              <select className="modal__input" value={form.engine} onChange={setField('engine')}>
                {ENGINES.map((e) => <option key={e.id} value={e.id}>{e.label}</option>)}
              </select>
            </Field>

            <Field label="Poll Interval (ms)">
              <input
                className="modal__input"
                type="number"
                min="250"
                value={form.pollIntervalMs}
                onChange={setField('pollIntervalMs')}
              />
            </Field>

            <Field label="JDBC URL" full>
              <input
                className="modal__input"
                value={form.jdbcUrl}
                onChange={setField('jdbcUrl')}
                required
                placeholder="jdbc:mariadb://host:3306/db"
              />
            </Field>

            <Field label="Username">
              <input className="modal__input" value={form.username} onChange={setField('username')} required />
            </Field>

            <Field
              label="Password"
              hint={!isNew && target.passwordSet ? '저장된 비밀번호가 있음 — 비워두면 기존 값 유지' : undefined}
            >
              <PasswordInput
                className="modal__input"
                value={form.password}
                placeholder={!isNew && target.passwordSet ? PASSWORD_PLACEHOLDER : ''}
                onChange={onPwChange}
                required={isNew}
                autoComplete="new-password"
              />
            </Field>

            <Field label="Host Exporter URL" hint="node_exporter endpoint for off-host metrics (optional)" full>
              <input
                className="modal__input"
                value={form.hostExporterUrl}
                onChange={setField('hostExporterUrl')}
                placeholder="http://host:9100/metrics"
              />
            </Field>

            <Field label="Ops Username" hint="kill-session account (optional)">
              <input className="modal__input" value={form.opsUsername} onChange={setField('opsUsername')} />
            </Field>

            <Field
              label="Ops Password"
              hint={!isNew && target.opsPasswordSet ? '저장된 비밀번호가 있음 — 비워두면 기존 값 유지' : undefined}
            >
              <PasswordInput
                className="modal__input"
                value={form.opsPassword}
                placeholder={!isNew && target.opsPasswordSet ? PASSWORD_PLACEHOLDER : ''}
                onChange={onOpsPwChange}
                autoComplete="new-password"
              />
            </Field>

            <Field label="Hikari Max Pool">
              <input
                className="modal__input"
                type="number"
                min="1"
                value={form.hikariMaxPoolSize}
                onChange={setField('hikariMaxPoolSize')}
              />
            </Field>

            <Field label="Hikari Min Idle">
              <input
                className="modal__input"
                type="number"
                min="0"
                value={form.hikariMinimumIdle}
                onChange={setField('hikariMinimumIdle')}
              />
            </Field>

            <Field label="TCP Connect Timeout (ms)">
              <input
                className="modal__input"
                type="number"
                min="0"
                value={form.tcpConnectTimeoutMs}
                onChange={setField('tcpConnectTimeoutMs')}
              />
            </Field>

            <Field label="Socket Read Timeout (ms)">
              <input
                className="modal__input"
                type="number"
                min="0"
                value={form.socketReadTimeoutMs}
                onChange={setField('socketReadTimeoutMs')}
              />
            </Field>
          </div>

          {testResult && (
            <div className={`modal__result ${testResult.ok ? 'modal__result--ok' : 'modal__result--err'}`}>
              <div className="modal__result-title">
                {testResult.ok ? '✓ Connected' : '✗ Connection failed'}
              </div>
              <div className="modal__result-sub">
                {testResult.ok
                  ? `Handshake completed in ${testResult.latencyMs} ms.`
                  : testResult.message || 'Unknown error'}
              </div>
            </div>
          )}

          {error && (
            <div className="modal__result modal__result--err">
              <div className="modal__result-title">Save failed</div>
              <div className="modal__result-sub">{error}</div>
            </div>
          )}

          <div className="modal__actions">
            <button
              type="button"
              className="modal__btn modal__btn--ghost"
              onClick={handleTest}
              disabled={testing || saving}
            >{testing ? 'Testing…' : 'Test Connection'}</button>
            <div className="tgt-form__spacer" />
            <button
              type="button"
              className="modal__btn modal__btn--ghost"
              onClick={onClose}
              disabled={saving}
            >Cancel</button>
            <button
              type="submit"
              className="modal__btn modal__btn--primary"
              disabled={saving}
            >{saving ? 'Saving…' : isNew ? 'Create' : 'Save Changes'}</button>
          </div>
        </form>
      </div>
    </div>
  );
}

function Field({ label, hint, full, children }) {
  return (
    <label className={`modal__field ${full ? 'modal__field--full' : ''}`}>
      <span className="modal__field-label">
        {label}
        {hint && <span className="modal__field-hint"> — {hint}</span>}
      </span>
      {children}
    </label>
  );
}

function seed(target) {
  return {
    id:                target?.id                 ?? '',
    displayName:       target?.displayName        ?? '',
    engine:            target?.engine             ?? 'MARIADB',
    jdbcUrl:           target?.jdbcUrl            ?? '',
    username:          target?.username           ?? '',
    password:          '',
    pollIntervalMs:    target?.pollIntervalMs     ?? 1000,
    hostExporterUrl:   target?.hostExporterUrl    ?? '',
    opsUsername:       target?.opsUsername        ?? '',
    opsPassword:       '',
    hikariMaxPoolSize: target?.hikariMaxPoolSize  ?? 4,
    hikariMinimumIdle: target?.hikariMinimumIdle  ?? 1,
    tcpConnectTimeoutMs: target?.tcpConnectTimeoutMs ?? 5000,
    socketReadTimeoutMs: target?.socketReadTimeoutMs ?? 8000,
  };
}

function numOrNull(v) {
  if (v === '' || v == null) return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}
