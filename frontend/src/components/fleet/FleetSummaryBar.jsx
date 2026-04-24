import { useMemo } from 'react';
import { useMetricsStore } from '../../store/metricsStore.js';
import { useAlertsStore, selectFiring } from '../../store/alertsStore.js';
import { ONLINE_THRESHOLD_MS, STALE_THRESHOLD_MS } from '../../lib/healthScore.js';

/**
 * Fleet-wide counters. Subscribes to the metric store's `byKey` directly so
 * the counters refresh on every SSE snapshot (the whole object is replaced on
 * each ingest, which triggers zustand's shallow diff). Firing alert counts
 * come from the alerts store's `firing` map so they update on FIRE/RESOLVE
 * without polling.
 */
export default function FleetSummaryBar({ targets }) {
  const byKey  = useMetricsStore((s) => s.byKey);
  const firing = useAlertsStore(selectFiring);

  const counts = useMemo(() => {
    const now = Date.now();
    let online = 0, stale = 0, offline = 0;
    for (const t of targets) {
      const age = latestAgeFor(t.id, byKey, now);
      if (age == null || age > STALE_THRESHOLD_MS) offline++;
      else if (age <= ONLINE_THRESHOLD_MS) online++;
      else stale++;
    }

    let crit = 0, warn = 0;
    for (const a of firing) {
      const sev = String(a.severity || '').toUpperCase();
      if (sev === 'CRITICAL') crit++;
      else if (sev === 'WARNING') warn++;
    }
    return { online, stale, offline, crit, warn };
  }, [targets, byKey, firing]);

  const total = targets.length;

  return (
    <div className="fleet-summary">
      <div className="fleet-summary__group">
        <span className="fleet-summary__stat">
          <b>{total}</b>
          <span>Targets</span>
        </span>
        <span className="fleet-summary__sep" aria-hidden>|</span>
        <span className="fleet-summary__stat fleet-summary__stat--ok">
          <b>{counts.online}</b>
          <span>Online</span>
        </span>
        {counts.stale > 0 && (
          <span className="fleet-summary__stat fleet-summary__stat--warn">
            <b>{counts.stale}</b>
            <span>Stale</span>
          </span>
        )}
        {counts.offline > 0 && (
          <span className="fleet-summary__stat fleet-summary__stat--crit">
            <b>{counts.offline}</b>
            <span>Offline</span>
          </span>
        )}
      </div>

      <div className="fleet-summary__group">
        <span className={`fleet-summary__stat ${counts.crit > 0 ? 'fleet-summary__stat--crit' : ''}`}>
          <b>{counts.crit}</b>
          <span>Critical</span>
        </span>
        <span className={`fleet-summary__stat ${counts.warn > 0 ? 'fleet-summary__stat--warn' : ''}`}>
          <b>{counts.warn}</b>
          <span>Warnings</span>
        </span>
      </div>
    </div>
  );
}

function latestAgeFor(targetId, byKey, nowMs) {
  const prefix = `${targetId}::`;
  let latest = null;
  for (const k of Object.keys(byKey)) {
    if (!k.startsWith(prefix)) continue;
    const arr = byKey[k];
    const last = arr && arr.length ? arr[arr.length - 1] : null;
    if (!last) continue;
    const ts = Date.parse(last.timestamp);
    if (Number.isFinite(ts) && (latest == null || ts > latest)) latest = ts;
  }
  return latest == null ? null : nowMs - latest;
}
