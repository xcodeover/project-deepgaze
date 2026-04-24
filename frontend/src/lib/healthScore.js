import { pick } from './rowAccess.js';

/**
 * Pure-frontend health score for a single target.
 *
 * Weights (clamped to [0, 100]):
 *   Start:           100
 *   Saturation:      -40 if sessionUtilPct > 90%
 *                    -20 if sessionUtilPct > 75%
 *   Alerts:          -30 per CRITICAL  (cap -60)
 *                    -10 per WARNING   (cap -30)
 *   Stale stream:    -20 if last snapshot age > 30 000 ms
 *
 * The calculator is pure so FleetDashboard / TargetCard can call it directly
 * without threading selectors. Inputs:
 *
 *   saturationRow:    latest dbSaturation snapshot's row (or null)
 *   firingAlerts:     list of AlertState objects for THIS target only
 *   lastSnapshotMs:   wall-clock ms of the most recent snapshot across any
 *                     group for this target, or null if none seen
 *   nowMs:            current wall clock (injectable for deterministic tests)
 *
 * Returns a breakdown so the card UI can render penalties (tooltips,
 * "why is this 60?") without recomputing.
 */

export const STALE_THRESHOLD_MS  = 30_000;
export const ONLINE_THRESHOLD_MS = 15_000;

export function computeHealthScore({
  saturationRow = null,
  firingAlerts = [],
  lastSnapshotMs = null,
  nowMs = Date.now(),
}) {
  const reasons = [];
  let score = 100;

  /* ---- Saturation ---- */
  const sessPct = saturationRow
    ? toNum(pick(saturationRow, 'session_utilization_pct', 'sessionUtilizationPct'))
    : null;
  if (sessPct != null) {
    if (sessPct > 90) {
      score -= 40;
      reasons.push({ kind: 'saturation', weight: -40, label: `Sessions ${sessPct.toFixed(0)}%` });
    } else if (sessPct > 75) {
      score -= 20;
      reasons.push({ kind: 'saturation', weight: -20, label: `Sessions ${sessPct.toFixed(0)}%` });
    }
  }

  /* ---- Alerts ---- */
  let critCount = 0;
  let warnCount = 0;
  for (const a of firingAlerts || []) {
    const sev = String(a.severity || '').toUpperCase();
    if (sev === 'CRITICAL') critCount++;
    else if (sev === 'WARNING') warnCount++;
  }
  const critPenalty = Math.min(critCount * 30, 60);
  const warnPenalty = Math.min(warnCount * 10, 30);
  if (critPenalty > 0) {
    score -= critPenalty;
    reasons.push({ kind: 'critical', weight: -critPenalty, label: `${critCount} critical` });
  }
  if (warnPenalty > 0) {
    score -= warnPenalty;
    reasons.push({ kind: 'warning', weight: -warnPenalty, label: `${warnCount} warning` });
  }

  /* ---- Stream staleness ---- */
  const age = lastSnapshotMs != null ? nowMs - lastSnapshotMs : null;
  if (age == null || age > STALE_THRESHOLD_MS) {
    score -= 20;
    reasons.push({
      kind: 'stale',
      weight: -20,
      label: age == null ? 'No data received' : `Stale ${Math.round(age / 1000)}s`,
    });
  }

  const clamped = Math.max(0, Math.min(100, Math.round(score)));
  return {
    score: clamped,
    grade: gradeOf(clamped),
    critCount,
    warnCount,
    lastSnapshotMs,
    ageMs: age,
    online: age != null && age <= ONLINE_THRESHOLD_MS,
    reasons,
  };
}

/** 🟢 80-100 OK · 🟡 50-79 WARN · 🔴 0-49 CRIT — the three colour bands. */
export function gradeOf(score) {
  if (score >= 80) return 'ok';
  if (score >= 50) return 'warn';
  return 'crit';
}

function toNum(v) {
  if (v == null) return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}
