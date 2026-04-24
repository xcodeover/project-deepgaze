import { pick } from '../lib/rowAccess.js';
import { glossaryFor } from '../lib/metricGlossary.js';

/**
 * Unified Cache/Buffer health tile. Renders one "Ratio KPI" per row of the
 * latest `ratios` snapshot. The backend emits rows with a common shape so
 * MariaDB's Buffer Pool Hit %, Oracle's Buffer Cache Hit %, and MSSQL's
 * Page Life Expectancy all slot into the same presentation.
 *
 *   row = { metric, label, value, unit, quality, note }
 *     quality: 'good' | 'warn' | 'bad'   (threshold classification from the collector)
 *     unit:    'pct'  | 'sec' | 'ratio' | 'count'
 *
 * Performance Diff — when `replayActive`, renders a "Live: X (▲ Y%)" sub-line
 * under each replay value so the operator can see how the DB has moved since
 * the incident without leaving replay mode.
 */
export default function RatiosTile({ snapshot, liveSnapshot, replayActive }) {
  const rows = snapshot?.rows || [];
  if (rows.length === 0) return null;
  const liveRows = liveSnapshot?.rows || [];
  return (
    <div className="ratios">
      {rows.map((r, i) => {
        const label = pick(r, 'label', 'metric') ?? 'metric';
        const value = pick(r, 'value');
        const unit  = pick(r, 'unit') ?? 'pct';
        const quality = (pick(r, 'quality') ?? 'good').toString().toLowerCase();
        const note = pick(r, 'note');
        const liveRow = replayActive ? matchRow(liveRows, r) : null;
        const liveValue = liveRow ? pick(liveRow, 'value') : null;
        const explain = glossaryFor(label) || glossaryFor(pick(r, 'metric'));
        return (
          <div key={`${label}-${i}`} className={`ratio ratio--${quality}`}>
            <div className="ratio__label" title={explain || undefined}>
              {label}
              {explain && <span className="ratio__info" aria-hidden="true">ⓘ</span>}
            </div>
            <div className="ratio__value">{formatValue(value, unit)}</div>
            {replayActive && liveValue != null && (
              <LiveDelta from={value} to={liveValue} unit={unit}
                higherIsBetter={metricHigherIsBetter(r)} />
            )}
            {note ? <div className="ratio__note">{note}</div> : null}
          </div>
        );
      })}
    </div>
  );
}

function matchRow(liveRows, replayRow) {
  if (!liveRows || liveRows.length === 0) return null;
  const replayKey = (pick(replayRow, 'metric') ?? pick(replayRow, 'label') ?? '').toString();
  if (!replayKey) return null;
  return liveRows.find((lr) => {
    const lk = (pick(lr, 'metric') ?? pick(lr, 'label') ?? '').toString();
    return lk === replayKey;
  }) ?? null;
}

/**
 * Rough directional heuristic derived from the row's metric identifier.
 * Hit-ratio / life-expectancy metrics reward higher values; miss-rate,
 * wait, latency metrics reward lower values. Anything we can't classify
 * falls back to `null` (neutral tint, arrow still rendered).
 */
function metricHigherIsBetter(row) {
  const name = ((pick(row, 'metric') ?? pick(row, 'label') ?? '') + '').toLowerCase();
  if (!name) return null;
  if (/miss|wait|latency|lag|queue|deadlock|rollback/.test(name)) return false;
  if (/hit|ratio|life|cache|efficiency|availability/.test(name)) return true;
  return null;
}

function LiveDelta({ from, to, unit, higherIsBetter }) {
  const a = Number(from);
  const b = Number(to);
  if (!Number.isFinite(a) || !Number.isFinite(b)) return null;
  const diff = b - a;
  const arrow = diff > 0 ? '▲' : diff < 0 ? '▼' : '■';
  const tone = toneFor(diff, higherIsBetter);
  return (
    <div className={`ratio__delta ratio__delta--${tone}`}>
      Live: <span className="ratio__delta-value">{formatValue(to, unit)}</span>
      {diff !== 0 && (
        <span className="ratio__delta-chip">
          {arrow} {formatDelta(diff, a, unit)}
        </span>
      )}
    </div>
  );
}

function toneFor(diff, higherIsBetter) {
  if (diff === 0 || higherIsBetter == null) return 'neutral';
  const better = higherIsBetter ? diff > 0 : diff < 0;
  return better ? 'up' : 'down';
}

function formatDelta(diff, base, unit) {
  const abs = Math.abs(diff);
  if (unit === 'pct') {
    return `${abs.toLocaleString(undefined, { maximumFractionDigits: 2 })} pp`;
  }
  if (unit === 'count') {
    return Math.trunc(abs).toLocaleString();
  }
  if (unit === 'sec') {
    return `${abs.toLocaleString(undefined, { maximumFractionDigits: 1 })}s`;
  }
  // Ratio / unknown → relative % change vs. replay baseline.
  if (base && Math.abs(base) > 1e-9) {
    const pct = (diff / Math.abs(base)) * 100;
    return `${Math.abs(pct).toLocaleString(undefined, { maximumFractionDigits: 1 })}%`;
  }
  return abs.toLocaleString(undefined, { maximumFractionDigits: 2 });
}

function formatValue(v, unit) {
  if (v == null) return '—';
  const n = Number(v);
  if (!Number.isFinite(n)) return String(v);
  switch (unit) {
    case 'pct':   return `${n.toLocaleString(undefined, { maximumFractionDigits: 2 })}%`;
    case 'sec':   return `${n.toLocaleString(undefined, { maximumFractionDigits: 0 })} s`;
    case 'ratio': return n.toLocaleString(undefined, { maximumFractionDigits: 3 });
    case 'count': return Math.trunc(n).toLocaleString();
    default:      return n.toLocaleString(undefined, { maximumFractionDigits: 2 });
  }
}
