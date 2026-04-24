import { useMemo } from 'react';
import { useMetricsStore, selectGroup } from '../store/metricsStore.js';
import { useAlertsStore, selectFiring } from '../store/alertsStore.js';
import { pick } from '../lib/rowAccess.js';

/**
 * At-a-glance "is the database OK right now?" banner above the bento grid.
 * Translates the wall of numbers into a single 0–100 score plus a 🟢/🟡/🔴
 * status badge so non-DBAs (PMs, on-call engineers, management) get the
 * headline without needing to interpret saturation percentages.
 *
 * The score starts at 100 and subtracts penalties for:
 *   - each firing alert (CRITICAL > WARNING)
 *   - session utilization in the elevated / critical bands
 *   - lock waits above the contention threshold
 *   - ratios the collector tagged quality='bad'
 *
 * The top 3 penalty reasons are surfaced so the banner also says *why*.
 */
export default function HealthBanner({ targetId, targetName }) {
  const firingAll    = useAlertsStore(selectFiring);
  const satSnaps     = useMetricsStore(selectGroup(targetId, 'dbSaturation'));
  const ratiosSnaps  = useMetricsStore(selectGroup(targetId, 'ratios'));

  const { score, status, reasons, hasData } = useMemo(() => {
    const firing = firingAll.filter((a) => a.targetId === targetId);
    const satRow = satSnaps.at(-1)?.rows?.[0] ?? null;
    const ratiosRows = ratiosSnaps.at(-1)?.rows ?? [];
    const hasData = !!satRow || ratiosRows.length > 0 || firing.length > 0;
    const result = computeHealthScore(firing, satRow, ratiosRows);
    return { ...result, hasData };
  }, [firingAll, satSnaps, ratiosSnaps, targetId]);

  if (!targetId) return null;

  const badge = status === 'healthy' ? 'Healthy'
             : status === 'warning' ? 'Warning'
             : 'Critical';
  const icon = status === 'healthy' ? '🟢'
            : status === 'warning' ? '🟡'
            : '🔴';

  return (
    <section className={`health-banner health-banner--${status}`}
             role="status"
             aria-label={`Database status: ${badge}, health score ${score} of 100`}>
      <div className="health-banner__score" title={`Composite health: ${score} / 100`}>
        <span className="health-banner__score-num">{hasData ? score : '—'}</span>
        <span className="health-banner__score-label">/ 100</span>
      </div>
      <div className="health-banner__body">
        <div className="health-banner__title">
          <span className={`health-banner__pill health-banner__pill--${status}`}>
            <span className="health-banner__pill-icon" aria-hidden="true">{icon}</span>
            {badge}
          </span>
          <span className="health-banner__target">
            {targetName || targetId}
          </span>
        </div>
        <div className="health-banner__reasons">
          {!hasData
            ? 'Waiting for the first metric snapshot…'
            : reasons.length === 0
              ? 'All monitored signals within their healthy ranges.'
              : reasons.slice(0, 3).join(' · ')}
        </div>
      </div>
    </section>
  );
}

function computeHealthScore(firing, satRow, ratiosRows) {
  let score = 100;
  const reasons = [];

  let crit = 0, warn = 0;
  for (const a of firing) {
    const s = (a.severity || '').toUpperCase();
    if (s === 'CRITICAL') crit++;
    else warn++;
  }
  if (crit > 0) {
    score -= crit * 30;
    reasons.push(`${crit} critical alert${crit === 1 ? '' : 's'} firing`);
  }
  if (warn > 0) {
    score -= warn * 10;
    reasons.push(`${warn} warning alert${warn === 1 ? '' : 's'} firing`);
  }

  if (satRow) {
    const sessPct  = toNum(pick(satRow, 'session_utilization_pct', 'sessionUtilizationPct'));
    const lockWait = toNum(pick(satRow, 'lock_wait_ms', 'lockWaitMs'));
    if (sessPct != null) {
      if (sessPct > 90) {
        score -= 20; reasons.push(`Sessions at ${Math.round(sessPct)}% (near limit)`);
      } else if (sessPct > 75) {
        score -= 10; reasons.push(`Sessions at ${Math.round(sessPct)}%`);
      }
    }
    if (lockWait != null && lockWait > 1000) {
      score -= 15;
      reasons.push(`Lock waits ${formatMs(lockWait)}`);
    }
  }

  const badRatios = ratiosRows.filter((r) =>
    String(pick(r, 'quality') ?? '').toLowerCase() === 'bad'
  );
  if (badRatios.length > 0) {
    score -= badRatios.length * 15;
    const first = pick(badRatios[0], 'label', 'metric') ?? 'ratio';
    reasons.push(
      badRatios.length === 1
        ? `${first} below healthy threshold`
        : `${badRatios.length} ratios below threshold`
    );
  }

  score = Math.max(0, Math.min(100, score));
  const status = score >= 80 ? 'healthy'
               : score >= 50 ? 'warning'
               : 'critical';

  return { score, status, reasons };
}

function toNum(v) {
  if (v == null) return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}
function formatMs(ms) {
  if (ms >= 1000) return `${(ms / 1000).toFixed(1)}s`;
  return `${Math.round(ms)}ms`;
}
