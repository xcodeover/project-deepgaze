import { useAlertsStore, selectFiring } from '../store/alertsStore.js';

const SEVERITY_CLASS = {
  CRITICAL: 'alert-row--critical',
  WARNING:  'alert-row--warning',
  INFO:     'alert-row--info',
};

/**
 * Passive renderer over the alerts store's firing map. Hidden (returns null)
 * when nothing is firing so it doesn't claim a card slot in the steady state.
 */
export default function FiringAlertsCard() {
  const firing = useAlertsStore(selectFiring);
  if (firing.length === 0) return null;

  const sorted = [...firing].sort(severityOrder);
  return (
    <div className="alert-banner">
      <div className="alert-banner__header">
        <h3 className="card__title">Firing Alerts ({sorted.length})</h3>
      </div>
      <table className="metric-table metric-table--compact">
        <thead>
          <tr>
            <th style={{ width: 80 }}>Severity</th>
            <th style={{ width: 180 }}>Rule</th>
            <th style={{ width: 160 }}>Target</th>
            <th style={{ width: 80 }}>Value</th>
            <th style={{ width: 120 }}>For</th>
            <th>Message</th>
          </tr>
        </thead>
        <tbody>
          {sorted.map((a) => (
            <tr key={`${a.ruleId}::${a.targetId}`} className={`alert-row ${SEVERITY_CLASS[a.severity] ?? ''}`}>
              <td><span className={`sev-pill sev-pill--${a.severity?.toLowerCase() ?? ''}`}>{a.severity}</span></td>
              <td>{a.ruleId}</td>
              <td>{a.targetId}</td>
              <td>{formatValue(a.lastValue)}</td>
              <td>{formatDuration(a.since)}</td>
              <td title={a.message}>{a.message}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function severityOrder(a, b) {
  const order = { CRITICAL: 0, WARNING: 1, INFO: 2 };
  return (order[a.severity] ?? 99) - (order[b.severity] ?? 99);
}

function formatValue(v) {
  if (v == null || Number.isNaN(v)) return '—';
  if (Number.isInteger(v)) return String(v);
  return Number(v).toPrecision(4);
}

function formatDuration(since) {
  if (!since) return '—';
  const deltaSec = Math.max(0, Math.round((Date.now() - Date.parse(since)) / 1000));
  if (deltaSec < 60)    return `${deltaSec}s`;
  if (deltaSec < 3600)  return `${Math.floor(deltaSec / 60)}m ${deltaSec % 60}s`;
  const h = Math.floor(deltaSec / 3600);
  const m = Math.floor((deltaSec % 3600) / 60);
  return `${h}h ${m}m`;
}
