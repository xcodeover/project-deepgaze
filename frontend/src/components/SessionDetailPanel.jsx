import { useEffect, useState } from 'react';
import { fetchSessionDetail, fetchExplain } from '../api/sessionDetail.js';
import ExplainPlanView from './ExplainPlanView.jsx';

/**
 * Side-drawer rendered as a fixed-position overlay. Opens when `session` is
 * set (the row the user clicked in ProcesslistTable). Lazy-fetches full
 * detail on open; EXPLAIN is a separate user action via the button.
 *
 * An AbortController is created per open — if the user clicks a different
 * row before the first fetch returns, the stale response is dropped rather
 * than overwriting the newer one.
 */
export default function SessionDetailPanel({ targetId, session, onClose }) {
  const [detail, setDetail]     = useState(null);
  const [error, setError]       = useState(null);
  const [loading, setLoading]   = useState(false);
  const [explain, setExplain]   = useState(null);
  const [explaining, setExpl]   = useState(false);

  useEffect(() => {
    if (!session || !targetId) return;
    const ctrl = new AbortController();
    setDetail(null);
    setError(null);
    setExplain(null);
    setLoading(true);

    fetchSessionDetail(targetId, session.id, { signal: ctrl.signal })
      .then((d) => { setDetail(d); setLoading(false); })
      .catch((e) => {
        if (e.name === 'AbortError') return;
        setError(String(e.message || e));
        setLoading(false);
      });

    return () => ctrl.abort();
    // Intentionally depends on session?.id, not `session` — a parent re-render
    // that produces a new reference to the same logical row must NOT refetch.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [targetId, session?.id]);

  // Esc-to-close — standard drawer affordance.
  useEffect(() => {
    if (!session) return;
    const onKey = (e) => { if (e.key === 'Escape') onClose?.(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [session, onClose]);

  if (!session) return null;

  const runExplain = async () => {
    const sql = detail?.info || session.info;
    if (!sql) {
      setExplain({ status: 'REJECTED', message: 'No SQL on this session.' });
      return;
    }
    setExpl(true);
    setExplain(null);
    try {
      const res = await fetchExplain(targetId, sql);
      setExplain(res);
    } catch (e) {
      setExplain({ status: 'FAILED', message: String(e.message || e) });
    } finally {
      setExpl(false);
    }
  };

  return (
    <>
      <div className="drawer__scrim" onClick={onClose} />
      <aside className="drawer" role="dialog" aria-label="Session detail">
        <header className="drawer__header">
          <div>
            <div className="drawer__title">Session {session.id}</div>
            <div className="drawer__sub">{targetId}</div>
          </div>
          <button type="button" className="drawer__close" onClick={onClose} aria-label="Close">×</button>
        </header>

        <div className="drawer__body">
          {loading && <div className="placeholder">Loading session detail…</div>}
          {error && <div className="drawer__error">Failed to load: {error}</div>}

          {detail && !detail.found && (
            <div className="drawer__notice">
              Session {session.id} is no longer active — it disconnected between the
              last processlist snapshot and your click.
            </div>
          )}

          {detail && detail.found && (
            <>
              <Section title="Session">
                <KV k="User"    v={detail.user} />
                <KV k="Host"    v={detail.host} />
                <KV k="DB"      v={detail.db} />
                <KV k="Command" v={detail.command} />
                <KV k="Time"    v={detail.timeSecs != null ? `${detail.timeSecs}s` : null} />
                <KV k="State"   v={detail.state} />
              </Section>

              {detail.trxId && (
                <Section title="InnoDB Transaction">
                  <KV k="Trx ID"         v={detail.trxId} />
                  <KV k="State"          v={detail.trxState} />
                  <KV k="Started"        v={detail.trxStarted} />
                  <KV k="Rows Locked"    v={detail.trxRowsLocked} />
                  <KV k="Rows Modified"  v={detail.trxRowsModified} />
                  <KV k="Isolation"      v={detail.trxIsolation} />
                </Section>
              )}

              {detail.waitEvent && (
                <Section title="Current Wait">
                  <KV k="Event"   v={detail.waitEvent} />
                  <KV k="Timer"   v={detail.waitTimer} />
                  <KV k="Schema"  v={detail.waitObjectSchema} />
                  <KV k="Object"  v={detail.waitObjectName} />
                </Section>
              )}

              <Section title="SQL">
                <pre className="drawer__sql">{detail.info || session.info || '(no SQL)'}</pre>
                <button
                  type="button"
                  className="drawer__btn"
                  onClick={runExplain}
                  disabled={explaining}
                >
                  {explaining ? 'Running EXPLAIN…' : 'Run EXPLAIN'}
                </button>
              </Section>

              {explain && <ExplainSection result={explain} />}
            </>
          )}
        </div>
      </aside>
    </>
  );
}

function Section({ title, children }) {
  return (
    <section className="drawer__section">
      <h4 className="drawer__section-title">{title}</h4>
      {children}
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

function ExplainSection({ result }) {
  if (result.status === 'OK') {
    return (
      <Section title="EXPLAIN Plan">
        <ExplainPlanView plan={result.plan} />
      </Section>
    );
  }
  const tone =
    result.status === 'REJECTED'    ? 'drawer__notice' :
    result.status === 'UNSUPPORTED' ? 'drawer__notice' :
                                      'drawer__error';
  return (
    <Section title="EXPLAIN Plan">
      <div className={tone}>{result.message}</div>
    </Section>
  );
}
