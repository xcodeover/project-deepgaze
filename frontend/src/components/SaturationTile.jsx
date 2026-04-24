import { pick } from '../lib/rowAccess.js';
import { glossaryFor } from '../lib/metricGlossary.js';

/**
 * Top-right tile: engine-agnostic saturation KPI trio — session utilization,
 * lock-wait pressure, and (when available) a host-derived utilization hint.
 * Falls back to BentoTile's empty skeleton when a target hasn't reported a
 * `dbSaturation` snapshot yet, so the 3x3 grid never reflows.
 *
 * Performance Diff — while `replayActive`, each KPI shows the live value and
 * the recovery direction (▲ red = worse since incident, ▼ green = recovering).
 */
export default function SaturationTile({ saturationSnapshots, liveSnapshot, replayActive }) {
  const latest = saturationSnapshots?.at(-1);
  const row = latest?.rows?.[0];
  if (!row) return <EmptyInner />;

  const sessPct = num(pick(row, 'session_utilization_pct', 'sessionUtilizationPct'));
  const connUsed = num(pick(row, 'threads_connected', 'sessions_connected', 'active_connections'));
  const connMax  = num(pick(row, 'max_connections', 'connection_limit'));
  const lockWait = num(pick(row, 'lock_wait_ms', 'lockWaitMs'));
  const running  = num(pick(row, 'threads_running', 'active_sessions', 'running'));

  const liveRow = replayActive ? liveSnapshot?.rows?.[0] : null;
  const liveSess     = liveRow ? num(pick(liveRow, 'session_utilization_pct', 'sessionUtilizationPct')) : null;
  const liveRunning  = liveRow ? num(pick(liveRow, 'threads_running', 'active_sessions', 'running')) : null;
  const liveLockWait = liveRow ? num(pick(liveRow, 'lock_wait_ms', 'lockWaitMs')) : null;

  return (
    <div className="sat-tile">
      <SatKpi
        label="Sessions"
        value={fmtPct(sessPct)}
        sub={connMax != null ? `${fmtInt(connUsed)} / ${fmtInt(connMax)}` : '—'}
        tone={sessPct > 90 ? 'critical' : sessPct > 75 ? 'warning' : 'ok'}
        delta={replayActive && liveSess != null ? {
          label: 'Live', value: fmtPct(liveSess),
          from: sessPct, to: liveSess, fmt: 'pct', higherIsBetter: false,
        } : null}
      />
      <SatKpi
        label="Running"
        value={running != null ? fmtInt(running) : '—'}
        sub="active threads"
        tone={running != null && running > 30 ? 'warning' : 'ok'}
        delta={replayActive && liveRunning != null ? {
          label: 'Live', value: fmtInt(liveRunning),
          from: running, to: liveRunning, fmt: 'count', higherIsBetter: false,
        } : null}
      />
      <SatKpi
        label="Lock Waits"
        value={lockWait != null ? `${fmtInt(lockWait)} ms` : '—'}
        sub="current lock wait"
        tone={lockWait != null && lockWait > 1000 ? 'critical' : lockWait > 0 ? 'warning' : 'ok'}
        delta={replayActive && liveLockWait != null ? {
          label: 'Live', value: `${fmtInt(liveLockWait)} ms`,
          from: lockWait, to: liveLockWait, fmt: 'ms', higherIsBetter: false,
        } : null}
      />
    </div>
  );
}

function EmptyInner() {
  return (
    <div className="sat-tile sat-tile--empty">
      <SatKpi label="Sessions"   value="—" sub="waiting…" />
      <SatKpi label="Running"    value="—" sub="waiting…" />
      <SatKpi label="Lock Waits" value="—" sub="waiting…" />
    </div>
  );
}

function SatKpi({ label, value, sub, tone = 'ok', delta = null }) {
  const explain = glossaryFor(label);
  return (
    <div className={`sat-kpi sat-kpi--${tone}`}>
      <div className="sat-kpi__label" title={explain || undefined}>
        {label}
        {explain && <span className="sat-kpi__info" aria-hidden="true">ⓘ</span>}
      </div>
      <div className="sat-kpi__value">{value}</div>
      {delta ? <KpiDelta {...delta} /> : <div className="sat-kpi__sub">{sub}</div>}
    </div>
  );
}

function KpiDelta({ label, value, from, to, fmt, higherIsBetter }) {
  const a = Number(from);
  const b = Number(to);
  const diff = Number.isFinite(a) && Number.isFinite(b) ? b - a : NaN;
  const arrow = diff > 0 ? '▲' : diff < 0 ? '▼' : '■';
  const tone = Number.isFinite(diff) && diff !== 0
    ? ((higherIsBetter ? diff > 0 : diff < 0) ? 'up' : 'down')
    : 'neutral';
  return (
    <div className={`sat-kpi__delta sat-kpi__delta--${tone}`}>
      <span className="sat-kpi__delta-live">{label}: {value}</span>
      {Number.isFinite(diff) && diff !== 0 && (
        <span className="sat-kpi__delta-chip">{arrow} {fmtDelta(diff, a, fmt)}</span>
      )}
    </div>
  );
}

function fmtDelta(diff, base, fmt) {
  const abs = Math.abs(diff);
  if (fmt === 'pct')   return `${abs.toLocaleString(undefined, { maximumFractionDigits: 1 })} pp`;
  if (fmt === 'count') return Math.trunc(abs).toLocaleString();
  if (fmt === 'ms')    return `${Math.trunc(abs).toLocaleString()} ms`;
  if (base && Math.abs(base) > 1e-9) {
    const pct = (diff / Math.abs(base)) * 100;
    return `${Math.abs(pct).toLocaleString(undefined, { maximumFractionDigits: 1 })}%`;
  }
  return abs.toLocaleString(undefined, { maximumFractionDigits: 2 });
}

function num(v) {
  if (v == null) return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}
function fmtPct(v) {
  if (v == null) return '—';
  return `${v.toLocaleString(undefined, { maximumFractionDigits: 1 })}%`;
}
function fmtInt(v) {
  if (v == null) return '—';
  return Math.trunc(v).toLocaleString();
}
