import { Fragment, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import Prism from 'prismjs';
import 'prismjs/components/prism-sql.js';
import { fetchDigestTrend, searchQueryHistory } from '../api/queryHistory.js';

/**
 * Centered modal for searching a target's historical query digests.
 *
 * Incremental features layered onto the original drawer→modal port:
 *   - Stability: AbortController cancels in-flight requests when filters
 *     change or the modal closes; a 7-day hard cap keeps the backend from
 *     being asked to scan open-ended ranges.
 *   - Accessibility: focus is trapped inside the modal and returned to the
 *     opener; ↑/↓/Home/End navigate rows and Enter/Space toggles the SQL
 *     expansion.
 *   - UX: search box is debounced (500 ms) so live typing doesn't spam the
 *     backend; numeric columns are click-to-sort (client-side over the
 *     current page); filter state is mirrored into the URL so a link
 *     reproduces the same view on reload.
 *
 * Latency numbers arrive in engine-native units (ps / 100ns / µs); we
 * normalise to milliseconds inline so the grid is comparable across targets.
 */
const SOURCES = [
  { id: '',            label: 'All' },
  { id: 'topDigests',  label: 'Top Digests' },
  { id: 'slowQueries', label: 'Slow Queries' },
];

const PRESETS = [
  { id: '15m', label: '15m', ms: 15 * 60 * 1000 },
  { id: '1h',  label: '1h',  ms: 60 * 60 * 1000 },
  { id: '6h',  label: '6h',  ms: 6  * 60 * 60 * 1000 },
  { id: '24h', label: '24h', ms: 24 * 60 * 60 * 1000 },
];

const PAGE           = 100;
const TREND_BUCKETS  = 24;
const SEARCH_DEBOUNCE_MS = 500;
// Hard cap on range length. Our default retention is 24h so anything beyond a
// week is almost always a mis-typed datetime-local. Reject early with a clear
// message rather than asking the backend to scan nothing.
const MAX_SPAN_MS = 7 * 24 * 60 * 60 * 1000;
const URL_KEYS = ['qh', 'qhTarget', 'qhFrom', 'qhTo', 'qhQ', 'qhSource', 'qhPreset'];

export default function QueryHistoryPanel({ open, onClose, targetId, targetName }) {
  const [preset, setPreset] = useState('1h');
  const [fromLocal, setFromLocal] = useState(() => toLocalInput(Date.now() - 60 * 60 * 1000));
  const [toLocal,   setToLocal]   = useState(() => toLocalInput(Date.now()));
  const [q, setQ]               = useState('');
  const [source, setSource]     = useState('');
  const [items, setItems]       = useState([]);
  const [total, setTotal]       = useState(0);
  const [offset, setOffset]     = useState(0);
  const [loading, setLoading]   = useState(false);
  const [error, setError]       = useState(null);
  const [expanded, setExpanded] = useState(null);
  const [trend, setTrend]       = useState({ bucketMs: 0, buckets: 0, fromMs: 0, byDigest: {} });
  const [lastSubmitted, setLastSubmitted] = useState(null);
  const [sort, setSort]         = useState({ col: null, dir: 'desc' });

  const trendSeq      = useRef(0);
  const abortRef      = useRef(null);
  const modalRef      = useRef(null);
  const openerRef     = useRef(null);
  const tbodyRef      = useRef(null);
  const debounceRef   = useRef(null);

  const cancelInFlight = useCallback(() => {
    const c = abortRef.current;
    if (c) {
      abortRef.current = null;
      try { c.abort(); } catch { /* ignore */ }
    }
  }, []);

  const cancelDebounced = useCallback(() => {
    if (debounceRef.current) {
      clearTimeout(debounceRef.current);
      debounceRef.current = null;
    }
  }, []);

  const run = useCallback(async (nextOffset = 0, overrides = null) => {
    if (!targetId) return;
    const from = overrides?.fromLocal ?? fromLocal;
    const to   = overrides?.toLocal   ?? toLocal;
    const fromMs = fromLocalToEpoch(from);
    const toMs   = fromLocalToEpoch(to);
    if (!Number.isFinite(fromMs) || !Number.isFinite(toMs)) {
      setError('Invalid date range.');
      return;
    }
    if (toMs < fromMs) {
      setError('End time must be after start time.');
      return;
    }
    if (toMs - fromMs > MAX_SPAN_MS) {
      setError('Time range exceeds 7 days. Narrow the window to run the search.');
      return;
    }

    cancelInFlight();
    const controller = new AbortController();
    abortRef.current = controller;

    setLoading(true);
    setError(null);
    const seq = ++trendSeq.current;
    try {
      const res = await searchQueryHistory({
        targetId, fromMs, toMs, q, source, limit: PAGE, offset: nextOffset,
        signal: controller.signal,
      });
      if (seq !== trendSeq.current) return;
      const nextItems = res.items || [];
      setItems(nextItems);
      setTotal(res.total || 0);
      setOffset(nextOffset);
      setLastSubmitted({ fromMs, toMs, q, source });

      // Sparkline fetch shares the same AbortController so a preset switch
      // mid-flight tears down both requests together.
      const digests = nextItems.map((e) => e.digest).filter(Boolean);
      if (digests.length > 0) {
        fetchDigestTrend({
          targetId, fromMs, toMs, digests, buckets: TREND_BUCKETS,
          signal: controller.signal,
        })
          .then((t) => {
            if (seq !== trendSeq.current) return;
            setTrend(normaliseTrend(t));
          })
          .catch(() => { /* sparklines are best-effort, abort is silent */ });
      } else {
        setTrend({ bucketMs: 0, buckets: 0, fromMs: 0, byDigest: {} });
      }
    } catch (err) {
      // Abort during preset change or modal close — the next run has already
      // taken over the UI, so we swallow it. All other errors surface as toast.
      if (err?.name === 'AbortError') return;
      if (seq !== trendSeq.current) return;
      setError(err.message || String(err));
      setItems([]);
      setTotal(0);
      setTrend({ bucketMs: 0, buckets: 0, fromMs: 0, byDigest: {} });
    } finally {
      if (seq === trendSeq.current) setLoading(false);
      if (abortRef.current === controller) abortRef.current = null;
    }
  }, [targetId, fromLocal, toLocal, q, source, cancelInFlight]);

  // Auto-run on open. If the opener URL carries qh=1 with frozen timestamps we
  // reproduce that view; otherwise we default to the most recent hour so the
  // operator sees live activity without hitting Search first.
  useEffect(() => {
    if (!open || !targetId) return;
    const seeded = readQhUrl();
    let nextFrom, nextTo, nextPreset, nextQ, nextSource;
    if (seeded && Number.isFinite(seeded.fromMs) && Number.isFinite(seeded.toMs) && seeded.toMs > seeded.fromMs) {
      nextFrom   = toLocalInput(seeded.fromMs);
      nextTo     = toLocalInput(seeded.toMs);
      nextPreset = seeded.preset || 'custom';
      nextQ      = seeded.q || '';
      nextSource = seeded.source || '';
    } else {
      const nowMs  = Date.now();
      nextFrom   = toLocalInput(nowMs - PRESETS.find((p) => p.id === '1h').ms);
      nextTo     = toLocalInput(nowMs);
      nextPreset = '1h';
      nextQ      = '';
      nextSource = '';
    }
    setPreset(nextPreset);
    setFromLocal(nextFrom);
    setToLocal(nextTo);
    setQ(nextQ);
    setSource(nextSource);
    setExpanded(null);
    setSort({ col: null, dir: 'desc' });
    run(0, { fromLocal: nextFrom, toLocal: nextTo });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, targetId]);

  // Focus trap + Escape + opener-focus restore. Runs only on open transitions
  // so filter-state re-renders don't steal focus out of whichever input the
  // user is typing in.
  useEffect(() => {
    if (!open) return;
    openerRef.current = (document.activeElement instanceof HTMLElement) ? document.activeElement : null;

    const focusFirst = () => {
      const node = modalRef.current;
      if (!node) return;
      const first = node.querySelector(
        'input:not([disabled]), select:not([disabled]), button:not([disabled]), [tabindex]:not([tabindex="-1"])'
      );
      if (first instanceof HTMLElement) first.focus();
    };
    const t = setTimeout(focusFirst, 0);

    const onKey = (e) => {
      if (e.key === 'Escape') { e.preventDefault(); onClose(); return; }
      if (e.key !== 'Tab') return;
      const node = modalRef.current;
      if (!node) return;
      const focusables = Array.from(node.querySelectorAll(
        'input:not([disabled]), select:not([disabled]), textarea:not([disabled]), button:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])'
      )).filter((el) => el.offsetParent !== null);
      if (focusables.length === 0) return;
      const first = focusables[0];
      const last  = focusables[focusables.length - 1];
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    };
    window.addEventListener('keydown', onKey);

    return () => {
      clearTimeout(t);
      window.removeEventListener('keydown', onKey);
      const opener = openerRef.current;
      openerRef.current = null;
      // Restore on the next tick so React has finished unmounting the modal.
      if (opener && typeof opener.focus === 'function') {
        setTimeout(() => opener.focus(), 0);
      }
    };
  }, [open, onClose]);

  // Abort + debounce cleanup on close/unmount so a slow backend can't land
  // results on the next view and a pending debounced search can't fire after
  // the user has moved on.
  useEffect(() => {
    if (!open) { cancelInFlight(); cancelDebounced(); }
    return () => { cancelInFlight(); cancelDebounced(); };
  }, [open, cancelInFlight, cancelDebounced]);

  // Mirror filters into the URL whenever they change while the modal is open,
  // and strip them on close. replaceState keeps the back button clean.
  useEffect(() => {
    if (!open) return;
    const fromMs = fromLocalToEpoch(fromLocal);
    const toMs   = fromLocalToEpoch(toLocal);
    if (!Number.isFinite(fromMs) || !Number.isFinite(toMs)) return;
    writeQhUrl({ fromMs, toMs, q, source, preset, targetId });
  }, [open, fromLocal, toLocal, q, source, preset, targetId]);

  useEffect(() => {
    if (open) return;
    clearQhUrl();
  }, [open]);

  // Client-side sort over the current page. Server returns rows already
  // ordered by timestamp desc; sorting the 100-row page in the browser keeps
  // the endpoint simple and sort + pagination from compounding.
  const sortedItems = useMemo(() => {
    if (!sort.col) return items;
    const dir = sort.dir === 'asc' ? 1 : -1;
    return [...items].sort((a, b) => (extractSortKey(a, sort.col) - extractSortKey(b, sort.col)) * dir);
  }, [items, sort]);

  // Page-scoped aggregates for the summary chips. These only describe the
  // current 100-row slice — total matches across the full time range is shown
  // separately in the header subtitle.
  const summary = useMemo(() => {
    if (items.length === 0) return null;
    let totalExec = 0, totalRows = 0, slowestMs = 0, slowest = null;
    const digests = new Set();
    for (const e of items) {
      totalExec += Number(e.countStar || 0);
      totalRows += Number(e.sumRowsExamined || 0);
      if (e.digest) digests.add(e.digest);
      const ms = avgMsOf(e);
      if (ms > slowestMs) { slowestMs = ms; slowest = e; }
    }
    return { totalExec, totalRows, distinct: digests.size, slowest, slowestMs };
  }, [items]);

  // p90 thresholds for the HOT / SLOW badges. Gated on a 5-row minimum so we
  // don't badge every row when the result set is too small to tell noise from
  // signal.
  const thresholds = useMemo(() => {
    if (items.length < 5) return { avgMsP90: Infinity, countP90: Infinity };
    const avgs   = items.map(avgMsOf).sort((a, b) => a - b);
    const counts = items.map((e) => Number(e.countStar || 0)).sort((a, b) => a - b);
    return { avgMsP90: percentile(avgs, 0.9), countP90: percentile(counts, 0.9) };
  }, [items]);

  if (!open) return null;

  const toggleSort = (col) => {
    setSort((s) => s.col === col
      ? { col, dir: s.dir === 'desc' ? 'asc' : 'desc' }
      : { col, dir: 'desc' }
    );
  };

  const onSubmit = (e) => { e.preventDefault(); cancelDebounced(); run(0); };

  const onQChange = (v) => {
    setQ(v);
    cancelDebounced();
    debounceRef.current = setTimeout(() => {
      debounceRef.current = null;
      run(0);
    }, SEARCH_DEBOUNCE_MS);
  };

  const nextPage = () => { cancelDebounced(); run(offset + PAGE); };
  const prevPage = () => { cancelDebounced(); run(Math.max(0, offset - PAGE)); };
  const onRetry  = () => { cancelDebounced(); run(offset); };

  // Export the currently displayed page (honouring the active sort) as CSV.
  // Scoping to the visible page keeps the click cheap — anyone who needs the
  // full result set can paginate and export per page, or widen the filter.
  const onExport = () => {
    if (sortedItems.length === 0) return;
    const header = ['when_iso', 'source', 'digest', 'count', 'avg_ms', 'rows_examined', 'sql'];
    const rows = sortedItems.map((e) => [
      e.tsEpochMs ? new Date(e.tsEpochMs).toISOString() : '',
      e.sourceGroup || '',
      e.digest || '',
      e.countStar ?? '',
      avgMsOf(e).toFixed(3),
      e.sumRowsExamined ?? '',
      (e.digestText || '').replace(/\s+/g, ' ').trim(),
    ]);
    const csv = [header, ...rows].map((r) => r.map(csvEscape).join(',')).join('\n');
    // Prepend BOM so Excel auto-detects UTF-8 for multi-byte SQL comments.
    const blob = new Blob(['\uFEFF', csv], { type: 'text/csv;charset=utf-8' });
    const url  = URL.createObjectURL(blob);
    const stamp = new Date().toISOString().replace(/[:.]/g, '').slice(0, 15);
    const a = document.createElement('a');
    a.href = url;
    a.download = `query-history-${safeFileSegment(targetName || targetId)}-${stamp}.csv`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  };

  const pickPreset = (id) => {
    const p = PRESETS.find((x) => x.id === id);
    if (!p) return;
    cancelDebounced();
    const nowMs = Date.now();
    const nextFrom = toLocalInput(nowMs - p.ms);
    const nextTo   = toLocalInput(nowMs);
    setPreset(id);
    setFromLocal(nextFrom);
    setToLocal(nextTo);
    run(0, { fromLocal: nextFrom, toLocal: nextTo });
  };

  const onCustomFrom = (v) => { setFromLocal(v); setPreset('custom'); };
  const onCustomTo   = (v) => { setToLocal(v);   setPreset('custom'); };

  const onRowKey = (e, rowKey) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      setExpanded((prev) => (prev === rowKey ? null : rowKey));
      return;
    }
    if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(e.key)) return;
    const scope = tbodyRef.current;
    if (!scope) return;
    const rows = Array.from(scope.querySelectorAll('tr.qh-row'));
    const idx  = rows.indexOf(e.currentTarget);
    if (idx < 0) return;
    let target = null;
    if      (e.key === 'ArrowDown') target = rows[Math.min(rows.length - 1, idx + 1)];
    else if (e.key === 'ArrowUp')   target = rows[Math.max(0, idx - 1)];
    else if (e.key === 'Home')      target = rows[0];
    else if (e.key === 'End')       target = rows[rows.length - 1];
    if (target && target !== e.currentTarget) {
      e.preventDefault();
      target.focus();
    }
  };

  const hasMore = offset + items.length < total;

  return (
    <div
      className="qh-overlay"
      role="dialog"
      aria-modal="true"
      aria-labelledby="qh-title"
    >
      <div className="qh-overlay__scrim" onClick={onClose} />
      <div className="qh-modal" ref={modalRef} onClick={(e) => e.stopPropagation()}>
        <header className="qh-modal__header">
          <div className="qh-modal__title">
            <span className="qh-modal__icon" aria-hidden>🔎</span>
            <div>
              <div id="qh-title" className="qh-modal__h">Query History</div>
              <div className="qh-modal__sub">
                {targetName || targetId} · {total.toLocaleString()} match{total === 1 ? '' : 'es'}
              </div>
            </div>
          </div>
          <button className="qh-modal__close" onClick={onClose} aria-label="Close">×</button>
        </header>

        <div className="qh-toolbar">
          <div className="qh-presets" role="tablist" aria-label="Time range">
            {PRESETS.map((p) => (
              <button
                key={p.id}
                type="button"
                role="tab"
                aria-selected={preset === p.id}
                className={`qh-preset ${preset === p.id ? 'is-active' : ''}`}
                onClick={() => pickPreset(p.id)}
              >{p.label}</button>
            ))}
            <button
              type="button"
              role="tab"
              aria-selected={preset === 'custom'}
              className={`qh-preset ${preset === 'custom' ? 'is-active' : ''}`}
              onClick={() => setPreset('custom')}
            >Custom</button>
          </div>

          <form className="qh-form" onSubmit={onSubmit}>
            <label className="qh-field">
              <span>From</span>
              <input type="datetime-local" value={fromLocal} onChange={(e) => onCustomFrom(e.target.value)} />
            </label>
            <label className="qh-field">
              <span>To</span>
              <input type="datetime-local" value={toLocal} onChange={(e) => onCustomTo(e.target.value)} />
            </label>
            <label className="qh-field qh-field--wide">
              <span>Search</span>
              <input
                type="text"
                value={q}
                onChange={(e) => onQChange(e.target.value)}
                placeholder="SQL substring or exact digest id"
              />
            </label>
            <label className="qh-field">
              <span>Source</span>
              <select value={source} onChange={(e) => setSource(e.target.value)}>
                {SOURCES.map((s) => <option key={s.id || 'all'} value={s.id}>{s.label}</option>)}
              </select>
            </label>
            <div className="qh-form__actions">
              <button type="submit" className="tgt-btn tgt-btn--primary" disabled={loading}>
                {loading ? 'Searching…' : 'Search'}
              </button>
            </div>
          </form>
        </div>

        {error && (
          <div className="qh-error" role="alert">
            <span className="qh-error__msg">{error}</span>
            <button
              type="button"
              className="qh-retry"
              onClick={onRetry}
              disabled={loading}
            >{loading ? 'Retrying…' : 'Retry'}</button>
          </div>
        )}

        {summary && (
          <div className="qh-summary" role="group" aria-label="Page summary">
            <div className="qh-chip">
              <span className="qh-chip__k">Page execs</span>
              <span className="qh-chip__v">{fmtNum(summary.totalExec)}</span>
            </div>
            <div className="qh-chip">
              <span className="qh-chip__k">Distinct digests</span>
              <span className="qh-chip__v">{fmtNum(summary.distinct)}</span>
            </div>
            <div className="qh-chip">
              <span className="qh-chip__k">Rows examined</span>
              <span className="qh-chip__v">{fmtNum(summary.totalRows)}</span>
            </div>
            {summary.slowest && (
              <div
                className="qh-chip qh-chip--warn"
                title={summary.slowest.digestText || ''}
              >
                <span className="qh-chip__k">Slowest avg</span>
                <span className="qh-chip__v">
                  {summary.slowestMs < 1 ? summary.slowestMs.toFixed(3)
                   : summary.slowestMs < 100 ? summary.slowestMs.toFixed(2)
                   : Math.round(summary.slowestMs).toLocaleString()} ms
                </span>
                <span className="qh-chip__hint">{shortDigest(summary.slowest.digest)}</span>
              </div>
            )}
          </div>
        )}

        <div className="qh-body">
          {items.length === 0 && !loading && (
            <div className="qh-empty">
              No matching queries in this window. Try widening the range or clearing the text filter.
            </div>
          )}
          {items.length > 0 && (
            <table className="qh-table">
              <thead>
                <tr>
                  <SortHeader col="when" label="When" sort={sort} onToggle={toggleSort} />
                  <th>Source</th>
                  <th>Digest</th>
                  <SortHeader col="count" label="Count" sort={sort} onToggle={toggleSort} align="right" />
                  <SortHeader col="avgMs" label="Avg (ms)" sort={sort} onToggle={toggleSort} align="right" />
                  <SortHeader col="rows"  label="Rows Exam." sort={sort} onToggle={toggleSort} align="right" />
                  <th>Trend</th>
                  <th>SQL</th>
                </tr>
              </thead>
              <tbody ref={tbodyRef}>
                {sortedItems.map((e) => {
                  const key = `${e.tsEpochMs}|${e.digest}|${e.sourceGroup}`;
                  const isOpen = expanded === key;
                  const countVal = Number(e.countStar || 0);
                  const avgVal   = avgMsOf(e);
                  const isHot  = countVal >= thresholds.countP90;
                  const isSlow = avgVal   >= thresholds.avgMsP90;
                  return (
                    <Fragment key={key}>
                      <tr
                        className={`qh-row ${isOpen ? 'qh-row--open' : ''}`}
                        tabIndex={0}
                        onClick={() => setExpanded(isOpen ? null : key)}
                        onKeyDown={(ev) => onRowKey(ev, key)}
                      >
                        <td>{formatTs(e.tsEpochMs)}</td>
                        <td>{e.sourceGroup}</td>
                        <td title={e.digest}>{shortDigest(e.digest)}</td>
                        <td style={{ textAlign: 'right' }}>
                          <span className="qh-cell-num">
                            <span>{fmtNum(e.countStar)}</span>
                            {isHot && <span className="qh-badge qh-badge--hot" title="Top 10% on this page by execution count">HOT</span>}
                          </span>
                        </td>
                        <td style={{ textAlign: 'right' }}>
                          <span className="qh-cell-num">
                            <span>{fmtMs(e.avgTimerWait, e.engineType)}</span>
                            {isSlow && <span className="qh-badge qh-badge--slow" title="Top 10% on this page by average latency">SLOW</span>}
                          </span>
                        </td>
                        <td style={{ textAlign: 'right' }}>{fmtNum(e.sumRowsExamined)}</td>
                        <td className="qh-trend-cell">
                          <Sparkline
                            values={pickSeries(trend, e.digest)}
                            buckets={trend.buckets || TREND_BUCKETS}
                            bucketMs={trend.bucketMs}
                            fromMs={trend.fromMs}
                          />
                        </td>
                        <td className="qh-sql-cell" title={e.digestText || ''}>
                          {firstLine(e.digestText)}
                        </td>
                      </tr>
                      {isOpen && (
                        <tr className="qh-row-expand">
                          <td colSpan={8}>
                            <SqlBlock text={e.digestText} />
                          </td>
                        </tr>
                      )}
                    </Fragment>
                  );
                })}
              </tbody>
            </table>
          )}
        </div>

        <footer className="qh-footer">
          <div>
            {lastSubmitted && items.length > 0 && (
              <>Showing {offset + 1}–{offset + items.length} of {total.toLocaleString()}</>
            )}
          </div>
          <div className="qh-footer__actions">
            <button
              type="button"
              className="tgt-btn tgt-btn--ghost"
              onClick={onExport}
              disabled={loading || items.length === 0}
              title="Download the current page as CSV"
            >⬇ Export CSV</button>
            <div className="qh-pager">
              <button type="button" className="tgt-btn tgt-btn--ghost"
                      onClick={prevPage} disabled={loading || offset === 0}>← Prev</button>
              <button type="button" className="tgt-btn tgt-btn--ghost"
                      onClick={nextPage} disabled={loading || !hasMore}>Next →</button>
            </div>
          </div>
        </footer>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */

function SortHeader({ col, label, sort, onToggle, align }) {
  const active = sort.col === col;
  const arrow  = active ? (sort.dir === 'asc' ? '↑' : '↓') : '';
  const ariaSort = active ? (sort.dir === 'asc' ? 'ascending' : 'descending') : 'none';
  const style = align === 'right' ? { textAlign: 'right' } : undefined;
  return (
    <th style={style} aria-sort={ariaSort}>
      <button
        type="button"
        className={`qh-sort ${active ? 'is-active' : ''}`}
        onClick={() => onToggle(col)}
      >
        <span>{label}</span>
        <span className="qh-sort__arrow" aria-hidden>{arrow || '↕'}</span>
      </button>
    </th>
  );
}

function SqlBlock({ text }) {
  const html = useMemo(() => {
    if (!text) return '';
    try {
      return Prism.highlight(text, Prism.languages.sql, 'sql');
    } catch {
      return escapeHtml(text);
    }
  }, [text]);
  const [copied, setCopied] = useState(false);
  const copy = () => {
    if (!text) return;
    navigator.clipboard?.writeText(text).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1200);
    }).catch(() => {});
  };
  return (
    <div className="qh-sql-block">
      <button type="button" className="qh-copy" onClick={copy}>
        {copied ? 'Copied ✓' : 'Copy'}
      </button>
      <pre className="qh-sql language-sql">
        <code className="language-sql" dangerouslySetInnerHTML={{ __html: html || '(empty)' }} />
      </pre>
    </div>
  );
}

/**
 * Compact inline SVG sparkline. Scales each value to the series max so small
 * bars remain visible when one bucket dominates. Hovering any bar reveals the
 * native browser tooltip with the bucket's time range and count — cheap, no
 * extra DOM, correctly anchored.
 */
function Sparkline({ values, buckets, bucketMs, fromMs }) {
  const arr = values && values.length ? values : new Array(buckets || TREND_BUCKETS).fill(0);
  const w = 84;
  const h = 22;
  const max = arr.reduce((m, v) => (v > m ? v : m), 0);
  const barW = arr.length > 0 ? w / arr.length : 0;
  const hasRange = Number.isFinite(fromMs) && Number.isFinite(bucketMs) && bucketMs > 0;
  return (
    <svg className="qh-spark" viewBox={`0 0 ${w} ${h}`} preserveAspectRatio="none" role="img">
      {arr.map((v, i) => {
        const ratio = max > 0 ? v / max : 0;
        const bh = Math.max(v > 0 ? 1 : 0, ratio * (h - 2));
        const tip = hasRange
          ? `${fmtHM(fromMs + i * bucketMs)}–${fmtHM(fromMs + (i + 1) * bucketMs)} · ${Number(v).toLocaleString()}`
          : `${Number(v).toLocaleString()}`;
        return (
          <rect
            key={i}
            x={i * barW}
            y={h - bh}
            width={Math.max(1, barW - 1)}
            height={bh}
            className={v > 0 ? 'qh-spark__bar' : 'qh-spark__bar--empty'}
          >
            <title>{tip}</title>
          </rect>
        );
      })}
    </svg>
  );
}

/* ------------------------------------------------------------------ */

function normaliseTrend(t) {
  const buckets = t?.buckets || 0;
  const byDigest = {};
  for (const p of t?.points || []) {
    if (!p.digest) continue;
    let arr = byDigest[p.digest];
    if (!arr) { arr = new Array(buckets).fill(0); byDigest[p.digest] = arr; }
    const idx = Math.max(0, Math.min(buckets - 1, p.bucket));
    arr[idx] += Number(p.sumCount ?? p.rowCount ?? 0);
  }
  return {
    bucketMs: t?.bucketMs || 0,
    buckets,
    fromMs:   t?.fromMs   || 0,
    byDigest,
  };
}

function pickSeries(trend, digest) {
  if (!digest) return null;
  return trend.byDigest[digest] || null;
}

function extractSortKey(e, col) {
  if (col === 'when')  return Number(e.tsEpochMs || 0);
  if (col === 'count') return Number(e.countStar || 0);
  if (col === 'rows')  return Number(e.sumRowsExamined || 0);
  if (col === 'avgMs') return avgMsOf(e);
  return 0;
}

function avgMsOf(e) {
  const n = Number(e?.avgTimerWait || 0);
  if (!Number.isFinite(n)) return 0;
  const eng = e.engineType;
  return eng === 'MARIADB' || eng === 'MYSQL' ? n / 1e9
       : eng === 'MSSQL'                      ? n / 1e4
       : eng === 'ORACLE'                     ? n / 1e3
       : n;
}

// Percentile via nearest-rank on a pre-sorted array. Returns +Infinity for
// empty inputs so threshold comparisons ("value >= threshold") naturally fail
// and no rows light up as anomalies when the page is too thin to have signal.
function percentile(sortedAsc, p) {
  if (!sortedAsc || sortedAsc.length === 0) return Infinity;
  const idx = Math.min(sortedAsc.length - 1, Math.max(0, Math.floor(sortedAsc.length * p)));
  return sortedAsc[idx];
}

function csvEscape(v) {
  const s = v == null ? '' : String(v);
  return /[",\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

function safeFileSegment(s) {
  return String(s || 'target').replace(/[^A-Za-z0-9_.-]+/g, '_').slice(0, 64);
}

function toLocalInput(ms) {
  const d = new Date(ms);
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function fromLocalToEpoch(s) {
  if (!s) return NaN;
  const t = new Date(s).getTime();
  return Number.isFinite(t) ? t : NaN;
}

function formatTs(ms) {
  if (!ms) return '';
  const d = new Date(ms);
  return d.toLocaleString();
}

function fmtHM(ms) {
  const d = new Date(ms);
  const pad = (n) => String(n).padStart(2, '0');
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function shortDigest(d) {
  if (!d) return '';
  if (d.length > 16) return d.slice(0, 13) + '…';
  return d;
}

function firstLine(s) {
  if (!s) return '';
  const line = s.split('\n')[0];
  return line.length > 120 ? line.slice(0, 117) + '…' : line;
}

function fmtNum(v) {
  if (v == null) return '';
  return Number(v).toLocaleString();
}

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

/**
 * Engine-specific timer_wait units → ms.
 *   MySQL/MariaDB emits picoseconds  (1 ms = 1e9)
 *   MSSQL emits 100-ns ticks         (1 ms = 1e4)
 *   Oracle emits microseconds        (1 ms = 1e3)
 * Unknown engine falls back to raw value.
 */
function fmtMs(v, engine) {
  if (v == null) return '';
  const n = Number(v);
  if (!Number.isFinite(n)) return '';
  const ms =
    engine === 'MARIADB' || engine === 'MYSQL' ? n / 1e9 :
    engine === 'MSSQL'                         ? n / 1e4 :
    engine === 'ORACLE'                        ? n / 1e3 :
    n;
  if (ms < 1)    return ms.toFixed(3);
  if (ms < 100)  return ms.toFixed(2);
  return Math.round(ms).toLocaleString();
}

/* ------------------------------------------------------------------ *
 *  URL sync — shareable link for the current filter/time-range view.
 * ------------------------------------------------------------------ */

function readQhUrl() {
  if (typeof window === 'undefined') return null;
  const u = new URL(window.location.href);
  if (u.searchParams.get('qh') !== '1') return null;
  const fromMs = Number(u.searchParams.get('qhFrom'));
  const toMs   = Number(u.searchParams.get('qhTo'));
  return {
    fromMs: Number.isFinite(fromMs) ? fromMs : NaN,
    toMs:   Number.isFinite(toMs)   ? toMs   : NaN,
    q:      u.searchParams.get('qhQ')      || '',
    source: u.searchParams.get('qhSource') || '',
    preset: u.searchParams.get('qhPreset') || 'custom',
  };
}

function writeQhUrl({ fromMs, toMs, q, source, preset, targetId }) {
  if (typeof window === 'undefined') return;
  const u = new URL(window.location.href);
  u.searchParams.set('qh', '1');
  if (targetId) u.searchParams.set('qhTarget', targetId); else u.searchParams.delete('qhTarget');
  u.searchParams.set('qhFrom', String(Math.floor(fromMs)));
  u.searchParams.set('qhTo',   String(Math.floor(toMs)));
  if (q)      u.searchParams.set('qhQ', q);           else u.searchParams.delete('qhQ');
  if (source) u.searchParams.set('qhSource', source); else u.searchParams.delete('qhSource');
  if (preset) u.searchParams.set('qhPreset', preset); else u.searchParams.delete('qhPreset');
  window.history.replaceState(null, '', u.toString());
}

function clearQhUrl() {
  if (typeof window === 'undefined') return;
  const u = new URL(window.location.href);
  let changed = false;
  for (const k of URL_KEYS) {
    if (u.searchParams.has(k)) { u.searchParams.delete(k); changed = true; }
  }
  if (changed) window.history.replaceState(null, '', u.toString());
}
