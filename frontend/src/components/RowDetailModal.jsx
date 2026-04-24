import { useEffect, useMemo, useState } from 'react';

/**
 * Universal row inspector for the table tiles (Processlist, Top SQL, Slow
 * Queries). Given any row object from a MetricSnapshot, renders every field
 * in a two-column key/value layout — with a JSON tab for when the operator
 * wants the raw payload for a bug report or a grep.
 *
 * Purely presentational: the caller owns the `open` state and passes the row
 * through `row`. Closing is unconditional — an ESC keypress or scrim click
 * always dismisses. No data fetching happens here, so the modal works
 * identically in Live mode (row from the live snapshot) and Replay mode
 * (row from the scrubbed snapshot) without caring which.
 *
 * Engine-agnostic: picks up whatever keys the row carries. MariaDB digest
 * columns, Oracle v$sql columns, and MSSQL dm_exec_query_stats columns all
 * render cleanly because we treat every key as opaque text with a generic
 * formatter.
 */
export default function RowDetailModal({ open, row, title = 'Row Detail', subtitle, onClose }) {
  const [tab, setTab] = useState('kv');

  useEffect(() => {
    if (!open) return;
    const onKey = (e) => { if (e.key === 'Escape') onClose?.(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  // Reset to the KV view every time the modal is reopened so the tab state
  // doesn't leak across two unrelated rows.
  useEffect(() => { if (open) setTab('kv'); }, [open]);

  const entries = useMemo(() => {
    if (!row || typeof row !== 'object') return [];
    return Object.entries(row);
  }, [row]);

  const jsonText = useMemo(() => {
    try { return JSON.stringify(row, null, 2); } catch { return String(row); }
  }, [row]);

  if (!open || !row) return null;

  return (
    <>
      <div className="drawer__scrim" onClick={onClose} />
      <div className="row-modal" role="dialog" aria-label={title}>
        <div className="row-modal__header">
          <div>
            <div className="row-modal__title">{title}</div>
            {subtitle && <div className="row-modal__sub">{subtitle}</div>}
          </div>
          <div className="row-modal__actions">
            <div className="view-toggle" role="tablist" aria-label="Detail view">
              <button
                type="button" role="tab"
                aria-selected={tab === 'kv'}
                className={`toggle-btn ${tab === 'kv' ? 'toggle-btn--active' : ''}`}
                onClick={() => setTab('kv')}
              >Key / Value</button>
              <button
                type="button" role="tab"
                aria-selected={tab === 'json'}
                className={`toggle-btn ${tab === 'json' ? 'toggle-btn--active' : ''}`}
                onClick={() => setTab('json')}
              >JSON</button>
            </div>
            <CopyButton text={jsonText} />
            <button className="history-drawer__close" onClick={onClose} aria-label="Close">×</button>
          </div>
        </div>
        <div className="row-modal__body">
          {tab === 'kv' ? <KvView entries={entries} /> : <JsonView text={jsonText} />}
        </div>
      </div>
    </>
  );
}

function KvView({ entries }) {
  if (!entries.length) {
    return <div className="placeholder">Row has no fields.</div>;
  }
  return (
    <dl className="row-kv">
      {entries.map(([k, v]) => (
        <div className="row-kv__row" key={k}>
          <dt className="row-kv__key">{k}</dt>
          <dd className="row-kv__val">{formatValue(v)}</dd>
        </div>
      ))}
    </dl>
  );
}

function JsonView({ text }) {
  return <pre className="row-modal__json">{text}</pre>;
}

function CopyButton({ text }) {
  const [done, setDone] = useState(false);
  const onCopy = async () => {
    try {
      await navigator.clipboard.writeText(text ?? '');
      setDone(true);
      setTimeout(() => setDone(false), 1200);
    } catch {
      // Clipboard API denied (insecure context / permissions) — swallow
      // silently; the JSON tab already shows the full payload for manual copy.
    }
  };
  return (
    <button type="button" className="row-modal__copy" onClick={onCopy}>
      {done ? 'Copied' : 'Copy JSON'}
    </button>
  );
}

/**
 * Renders one cell of the KV grid. The rules:
 *   - null/undefined → em-dash, muted.
 *   - Long SQL text  → preformatted block so newlines / indent survive.
 *   - Numbers        → grouped with locale separators for readability.
 *   - Everything else → toString() in a selectable span.
 * The long-text heuristic fires on any string > 80 chars OR containing a
 * newline — catches SQL bodies (digest_text, info, waiting_query) without
 * wrapping tiny identifiers.
 */
function formatValue(v) {
  if (v == null) return <span className="row-kv__empty">—</span>;
  if (typeof v === 'number') {
    return <span className="row-kv__num">{v.toLocaleString()}</span>;
  }
  if (typeof v === 'boolean') {
    return <span className="row-kv__bool">{String(v)}</span>;
  }
  const s = String(v);
  if (s.length > 80 || s.includes('\n')) {
    return <pre className="row-kv__pre">{s}</pre>;
  }
  return <span className="row-kv__text">{s}</span>;
}
