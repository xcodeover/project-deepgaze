import { useEffect, useState } from 'react';
import { apiFetch } from '../api/http.js';

/**
 * Query-plan viewer. POSTs the selected row to /api/targets/{id}/explain and
 * renders the typed ExplainResult: OK shows the parsed plan tree, REJECTED /
 * FAILED / UNSUPPORTED each get their own inline messaging.
 *
 * `query` shape is flexible: whatever the caller has (digest row, processlist
 * row, or a bare {sql} object). We forward it verbatim; the backend extracts
 * the SQL from digest_text / info / sql.
 */
export default function ExplainModal({ open, targetId, query, onClose }) {
  const [state, setState] = useState({ loading: false, error: null, data: null });

  useEffect(() => {
    if (!open || !query) return;
    setState({ loading: true, error: null, data: null });
    const controller = new AbortController();
    (async () => {
      try {
        const res = await apiFetch(`/api/targets/${encodeURIComponent(targetId)}/explain`, {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify(query),
          signal: controller.signal,
        });
        const text = await res.text();
        let parsed = null;
        try { parsed = JSON.parse(text); } catch { parsed = { raw: text }; }
        setState({ loading: false, error: null, data: { status: res.status, body: parsed } });
      } catch (err) {
        if (err.name === 'AbortError') return;
        setState({ loading: false, error: String(err), data: null });
      }
    })();
    return () => controller.abort();
  }, [open, targetId, query]);

  useEffect(() => {
    if (!open) return;
    const onKey = (e) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  if (!open) return null;

  const { loading, error, data } = state;

  return (
    <>
      <div className="drawer__scrim" onClick={onClose} />
      <div className="explain-modal" role="dialog" aria-label="Explain plan">
        <div className="explain-modal__header">
          <div>
            <div className="explain-modal__title">Explain Plan</div>
            <div className="explain-modal__sub">{targetId}</div>
          </div>
          <button className="history-drawer__close" onClick={onClose} aria-label="Close">×</button>
        </div>
        <div className="explain-modal__body">
          <QueryEcho query={query} />
          {loading && <div className="placeholder">Requesting plan…</div>}
          {error && <div className="placeholder placeholder--error">Error: {error}</div>}
          {data && <ResponseBlock data={data} />}
        </div>
      </div>
    </>
  );
}

function QueryEcho({ query }) {
  const pretty = typeof query === 'string' ? query : JSON.stringify(query, null, 2);
  return (
    <section className="explain-block">
      <div className="explain-block__heading">Query</div>
      <pre className="explain-block__code">{pretty || '(none)'}</pre>
    </section>
  );
}

function ResponseBlock({ data }) {
  const { status: httpStatus, body } = data;

  // Typed-status path (OK | REJECTED | FAILED | UNSUPPORTED) — the backend
  // returns 200 with this shape for every expected outcome so the UI can
  // render the reason inline rather than as a generic toast.
  const typed = body && typeof body === 'object' ? body.status : null;
  if (typed === 'OK') {
    return <PlanBlock plan={body.plan} />;
  }
  if (typed === 'REJECTED' || typed === 'FAILED' || typed === 'UNSUPPORTED') {
    const tone = typed === 'UNSUPPORTED' ? 'info' : 'warn';
    const heading =
      typed === 'UNSUPPORTED' ? 'Not supported on this engine yet'
      : typed === 'REJECTED'  ? 'Plan request rejected'
      : 'Engine reported an error';
    return (
      <section className={`explain-block explain-block--${tone}`}>
        <div className="explain-block__heading">{heading}</div>
        <pre className="explain-block__code">{body.message || '(no detail)'}</pre>
      </section>
    );
  }

  // Fallback — unknown shape, surface it verbatim.
  const tone = httpStatus >= 400 ? 'warn' : 'ok';
  return (
    <section className={`explain-block explain-block--${tone}`}>
      <div className="explain-block__heading">Backend response · HTTP {httpStatus}</div>
      <pre className="explain-block__code">{JSON.stringify(body, null, 2)}</pre>
    </section>
  );
}

/**
 * Renders the three plan shapes the backend emits:
 *   - MariaDB: native EXPLAIN FORMAT=JSON tree → pretty-printed JSON.
 *   - Oracle:  {format:"text", lines:[...]}   → joined lines in a monospace block.
 *   - MSSQL:   {format:"xml",  xml:"..."}      → raw XML in a monospace block.
 * Heuristic: if plan has a `format` field we dispatch on it, otherwise fall
 * back to the MariaDB JSON renderer so we stay forward-compatible with any
 * new engine that returns a structured tree.
 */
function PlanBlock({ plan }) {
  if (plan && typeof plan === 'object' && plan.format === 'text') {
    const text = Array.isArray(plan.lines) ? plan.lines.join('\n') : '(empty plan)';
    return (
      <section className="explain-block explain-block--ok">
        <div className="explain-block__heading">{plan.title || 'Execution plan'}</div>
        <pre className="explain-block__code">{text}</pre>
      </section>
    );
  }
  if (plan && typeof plan === 'object' && plan.format === 'xml') {
    return (
      <section className="explain-block explain-block--ok">
        <div className="explain-block__heading">{plan.title || 'Execution plan · XML'}</div>
        <pre className="explain-block__code">{plan.xml || '(empty plan)'}</pre>
      </section>
    );
  }
  return (
    <section className="explain-block explain-block--ok">
      <div className="explain-block__heading">Execution plan · EXPLAIN FORMAT=JSON</div>
      <pre className="explain-block__code">{JSON.stringify(plan, null, 2)}</pre>
    </section>
  );
}
