import { useMemo } from 'react';
import {
  useMetricsStore,
  selectGroup,
  selectLastSnapshotMs,
} from '../../store/metricsStore.js';
import { useAlertsStore, selectFiring } from '../../store/alertsStore.js';
import { computeHealthScore } from '../../lib/healthScore.js';
import { navigate } from '../../lib/router.js';
import HealthRing from './HealthRing.jsx';
import Sparkline from './Sparkline.jsx';

const RING_COLOUR = { ok: '#2dd4a4', warn: '#f5b431', crit: '#ef5a5a' };

/**
 * NOC wallboard card for one target. Reads live state from the stores — no
 * prop drilling of snapshots — and recomputes the health score every render.
 * Render cost: one ring SVG, one polyline SVG, plus string formatting. Cheap
 * enough to leave in a 1Hz-ticking view even with ~50 cards.
 *
 * The entire card is the click target (not just a "View" button) so operators
 * with a touch wallboard can punch through without hunting for a link.
 */
export default function TargetCard({ target }) {
  const satSnaps        = useMetricsStore(selectGroup(target.id, 'dbSaturation'));
  const activeSnaps     = useMetricsStore(selectGroup(target.id, 'activeSessions'));
  const lastSnapshotMs  = useMetricsStore(selectLastSnapshotMs(target.id));
  const firingAll       = useAlertsStore(selectFiring);

  const firingForTarget = useMemo(
    () => firingAll.filter((a) => a.targetId === target.id),
    [firingAll, target.id],
  );

  const satisfaction = useMemo(() => {
    const satRow = satSnaps.at(-1)?.rows?.[0] || null;
    return computeHealthScore({
      saturationRow: satRow,
      firingAlerts: firingForTarget,
      lastSnapshotMs,
    });
  }, [satSnaps, firingForTarget, lastSnapshotMs]);

  const sparkValues = useMemo(() => sessionCountSeries(activeSnaps), [activeSnaps]);

  const ringColour = RING_COLOUR[satisfaction.grade];
  const dotClass   = satisfaction.online ? 'dot dot--online'
                   : satisfaction.ageMs == null || satisfaction.ageMs > 30_000 ? 'dot dot--offline'
                   : 'dot dot--stale';
  const dotTitle   = satisfaction.ageMs == null ? 'No snapshots received'
                   : `Last snapshot ${formatAge(satisfaction.ageMs)} ago`;

  const handleOpen = () => navigate(`/targets/${encodeURIComponent(target.id)}`);
  const handleKey = (e) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      handleOpen();
    }
  };

  return (
    <article
      className={`tgt-card tgt-card--${satisfaction.grade}`}
      role="link"
      tabIndex={0}
      onClick={handleOpen}
      onKeyDown={handleKey}
      aria-label={`Open ${target.displayName} dashboard`}
    >
      <header className="tgt-card__header">
        <div className="tgt-card__title">
          <span className={dotClass} title={dotTitle} />
          <span className="tgt-card__name">{target.displayName}</span>
        </div>
        <span className={`tgt-badge tgt-badge--${String(target.engine).toLowerCase()}`}>
          {target.engine}
        </span>
      </header>

      <div className="tgt-card__body">
        <HealthRing
          score={satisfaction.score}
          grade={satisfaction.grade}
          label={`${target.displayName} health ${satisfaction.score}/100`}
        />
        <ul className="tgt-card__reasons">
          {satisfaction.reasons.length === 0 && (
            <li className="tgt-card__reason tgt-card__reason--ok">All checks green</li>
          )}
          {satisfaction.reasons.map((r, i) => (
            <li key={i} className={`tgt-card__reason tgt-card__reason--${r.kind}`}>
              <span className="tgt-card__reason-weight">{r.weight}</span>
              <span className="tgt-card__reason-label">{r.label}</span>
            </li>
          ))}
        </ul>
      </div>

      <footer className="tgt-card__footer">
        <div className="tgt-card__counts">
          <span className={`tgt-card__count ${satisfaction.critCount ? 'tgt-card__count--crit' : ''}`}>
            <b>{satisfaction.critCount}</b> critical
          </span>
          <span className={`tgt-card__count ${satisfaction.warnCount ? 'tgt-card__count--warn' : ''}`}>
            <b>{satisfaction.warnCount}</b> warning
          </span>
        </div>
        <Sparkline values={sparkValues} colour={ringColour} />
      </footer>
    </article>
  );
}

/**
 * activeSessions snapshots carry one row per session. Each snapshot's
 * rows.length ≈ concurrent active session count — a cheap proxy for load
 * that's valid across engines without waiting on a purpose-built counter.
 */
function sessionCountSeries(snapshots) {
  if (!snapshots || snapshots.length === 0) return [];
  return snapshots.slice(-60).map((s) => s.rows?.length ?? 0);
}

function formatAge(ms) {
  if (ms < 1000) return `${ms} ms`;
  if (ms < 60_000) return `${Math.round(ms / 1000)} s`;
  return `${Math.round(ms / 60_000)} m`;
}
