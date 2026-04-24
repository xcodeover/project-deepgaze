import { pick } from '../../lib/rowAccess.js';

/**
 * Slow / unindexed candidates from events_statements_summary_by_digest.
 * Ordered by AVG_TIMER_WAIT server-side so a rare-but-awful query surfaces
 * even when dwarfed by high-frequency fast ones.
 *
 * `scan_ratio` = rows_examined / rows_sent. A healthy indexed lookup is ≤ 2;
 * full-scan-and-filter queries balloon to hundreds or thousands — those rows
 * are red-tinted so unindexed scans stand out at a glance.
 */
const SCAN_RATIO_HOT = 100;

// Explicit column order — hides the raw digest hash (noise), keeps the text
// first, then headline numbers. Server already orders by avg_timer_wait DESC.
const COLS = [
  { key: 'digest_text',       label: 'DIGEST' },
  { key: 'count_star',        label: 'CALLS',          num: true },
  { key: 'avg_timer_wait',    label: 'AVG (ms)',       num: true, fmt: psToMs },
  { key: 'sum_rows_examined', label: 'ROWS EXAMINED',  num: true },
  { key: 'sum_rows_sent',     label: 'ROWS SENT',      num: true },
  { key: 'scan_ratio',        label: 'SCAN RATIO',     num: true, highlight: true },
  { key: 'sum_no_index_used', label: 'NO IDX',         num: true },
];

export default function SlowQueriesTable({ snapshot, onExplain, onInspect }) {
  if (!snapshot || !snapshot.rows?.length) {
    return <div className="placeholder">No slow-query data yet.</div>;
  }

  const clickable = typeof onInspect === 'function';

  return (
    <table className={`metric-table metric-table--compact ${clickable ? 'metric-table--clickable' : ''}`}>
      <thead>
        <tr>
          {COLS.map((c) => (
            <th key={c.key} style={c.num ? { textAlign: 'right' } : undefined}>{c.label}</th>
          ))}
          {onExplain && <th style={{ width: 60 }}></th>}
        </tr>
      </thead>
      <tbody>
        {snapshot.rows.map((row, i) => {
          const ratio = Number(pick(row, 'scan_ratio') ?? 0);
          const hot = ratio > SCAN_RATIO_HOT;
          return (
            <tr
              key={i}
              className={hot ? 'row--hot' : undefined}
              onClick={clickable ? () => onInspect(row) : undefined}
            >
              {COLS.map((c) => {
                const raw = pick(row, c.key);
                const text = c.fmt ? c.fmt(raw) : format(raw, c.num);
                return (
                  <td
                    key={c.key}
                    title={String(raw ?? '')}
                    style={c.num ? { textAlign: 'right' } : undefined}
                  >{text}</td>
                );
              })}
              {onExplain && (
                <td style={{ textAlign: 'right' }}>
                  <button
                    type="button" className="explain-btn"
                    onClick={(e) => { e.stopPropagation(); onExplain(row); }}
                  >Explain</button>
                </td>
              )}
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

function format(v, num) {
  if (v == null) return '';
  if (num) {
    const n = Number(v);
    return Number.isFinite(n) ? n.toLocaleString() : String(v);
  }
  return String(v);
}

function psToMs(v) {
  const n = Number(v);
  if (!Number.isFinite(n)) return '';
  return (n / 1e9).toLocaleString(undefined, { maximumFractionDigits: 2 });
}
