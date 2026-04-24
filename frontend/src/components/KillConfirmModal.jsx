import { useEffect, useState } from 'react';
import { killSession } from '../api/ops.js';

/**
 * Double-confirm modal for the Active Response kill-session action.
 *
 * Stage 1 shows the session preview and the target/PID so the operator can
 * verify they're about to kill the right row; typing "KILL" into the
 * confirmation box enables the submit button. This is intentional friction:
 * a KILL cannot be undone, and the pre-kill snapshot is the last information
 * the operator has before the row disappears from the next processlist poll.
 *
 * Stage 2 ("result") shows the server outcome with typed styling:
 *   - OK          → green success, auto-closes after 1.2s
 *   - REJECTED    → yellow notice (protected user / replication thread / gone)
 *   - UNAVAILABLE → yellow notice (ops pool not configured on the server)
 *   - FAILED      → red error (JDBC failure; server log has details)
 *   - 401 / 503   → red error (session lost or ops disabled globally)
 *
 * Authentication now rides on the session JWT (api/http.js). A 401 means the
 * token expired mid-flow — the shared fetch wrapper has already redirected
 * the app to /login, so here we just surface a clean message.
 */
export default function KillConfirmModal({ open, targetId, session, onClose, onKilled }) {
  const [confirmText, setConfirmText] = useState('');
  const [submitting, setSubmitting]   = useState(false);
  const [result, setResult]           = useState(null);

  useEffect(() => {
    if (open) {
      setConfirmText('');
      setResult(null);
      setSubmitting(false);
    }
  }, [open, session?.id]);

  useEffect(() => {
    if (!open) return;
    const onKey = (e) => { if (e.key === 'Escape' && !submitting) onClose?.(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, submitting, onClose]);

  if (!open || !session) return null;

  const canSubmit = confirmText.trim().toUpperCase() === 'KILL' && !submitting;

  const submit = async () => {
    setSubmitting(true);
    setResult(null);
    try {
      const res = await killSession(targetId, session.id);
      setResult(res);
      if (res.ok) {
        onKilled?.(session.id);
        setTimeout(() => onClose?.(), 1200);
      }
    } catch (e) {
      setResult({ ok: false, status: 0, body: { message: String(e.message || e) } });
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <>
      <div className="drawer__scrim" onClick={submitting ? undefined : onClose} />
      <div className="modal" role="dialog" aria-label="Kill session">
        <header className="modal__header modal__header--danger">
          <div>
            <div className="modal__title">Kill session {session.id}?</div>
            <div className="modal__sub">{targetId}</div>
          </div>
          <button
            type="button"
            className="drawer__close"
            onClick={onClose}
            disabled={submitting}
            aria-label="Close"
          >×</button>
        </header>

        <div className="modal__body">
          <SessionPreview session={session} />

          {!result && (
            <>
              <div className="drawer__notice">
                This sends <code>KILL {session.id}</code> to <b>{targetId}</b>.
                The running transaction will roll back and the connection drop.
                This cannot be undone.
              </div>

              <label className="modal__field">
                <span className="modal__field-label">
                  Type <code>KILL</code> to confirm
                </span>
                <input
                  type="text"
                  className="modal__input"
                  value={confirmText}
                  onChange={(e) => setConfirmText(e.target.value)}
                  autoFocus
                  autoComplete="off"
                  spellCheck={false}
                />
              </label>

              <div className="modal__actions">
                <button
                  type="button"
                  className="modal__btn modal__btn--ghost"
                  onClick={onClose}
                  disabled={submitting}
                >Cancel</button>
                <button
                  type="button"
                  className="modal__btn modal__btn--danger"
                  onClick={submit}
                  disabled={!canSubmit}
                >
                  {submitting ? 'Killing…' : `Kill ${session.id}`}
                </button>
              </div>
            </>
          )}

          {result && <ResultView result={result} />}
        </div>
      </div>
    </>
  );
}

function SessionPreview({ session }) {
  return (
    <section className="drawer__section">
      <h4 className="drawer__section-title">Pre-kill snapshot</h4>
      <KV k="PID"     v={session.id} />
      <KV k="User"    v={session.user} />
      <KV k="Host"    v={session.host} />
      <KV k="DB"      v={session.db} />
      <KV k="Command" v={session.command} />
      <KV k="Time"    v={session.time_secs != null ? `${session.time_secs}s` : null} />
      <KV k="State"   v={session.state} />
      {session.info && <pre className="drawer__sql">{session.info}</pre>}
    </section>
  );
}

function KV({ k, v }) {
  if (v == null || v === '') return null;
  return (
    <div className="drawer__kv">
      <span className="drawer__kv-k">{k}</span>
      <span className="drawer__kv-v">{String(v)}</span>
    </div>
  );
}

function ResultView({ result }) {
  if (result.ok) {
    const row = result.body?.session;
    return (
      <div className="modal__result modal__result--ok">
        <div className="modal__result-title">Killed.</div>
        {row && <div className="modal__result-sub">
          PID {row.id} · {row.user}@{row.host} · was running for {row.timeSecs}s
        </div>}
      </div>
    );
  }
  if (result.status === 401) {
    return (
      <div className="drawer__error">
        Session expired — please sign in again.
      </div>
    );
  }
  const msg  = result.body?.message || `HTTP ${result.status}`;
  const kind = result.body?.status;
  const cls  = (kind === 'REJECTED' || kind === 'UNAVAILABLE' || result.status === 503)
    ? 'drawer__notice'
    : 'drawer__error';
  return <div className={cls}>{msg}</div>;
}
