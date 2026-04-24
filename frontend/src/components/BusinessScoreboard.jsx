/**
 * Horizontal "Bloomberg band" of large-number KPIs above the tile grid.
 *
 * Presentational — reads the most recent `businessMetrics` snapshot from the
 * store and renders one card per row. Returns null when no metrics are
 * configured for the target, so the parent layout row collapses cleanly.
 */
export default function BusinessScoreboard({ snapshot }) {
  if (!snapshot || !snapshot.rows?.length) return null;

  return (
    <div className="scoreboard" role="list" aria-label="Business metrics">
      {snapshot.rows.map((row) => (
        <KpiCard key={row.id} row={row} />
      ))}
    </div>
  );
}

function KpiCard({ row }) {
  const { label, value, format, error } = row;
  const display = error ? '—' : formatValue(value, format);
  return (
    <div
      role="listitem"
      className={`kpi ${error ? 'kpi--error' : ''}`}
      title={error ? `Query failed: ${error}` : undefined}
    >
      <div className="kpi__label">{label}</div>
      <div className="kpi__value">{display}</div>
    </div>
  );
}

function formatValue(v, format) {
  if (v == null || v === '') return '—';
  const n = Number(v);
  if (!Number.isFinite(n)) return String(v);

  switch (format) {
    case 'integer':
      return Math.trunc(n).toLocaleString();
    case 'currency':
      return n.toLocaleString(undefined, {
        style: 'currency',
        currency: 'USD',
        maximumFractionDigits: 2,
      });
    case 'percent':
      return `${n.toLocaleString(undefined, { maximumFractionDigits: 1 })}%`;
    case 'number':
    default:
      return n.toLocaleString(undefined, { maximumFractionDigits: 2 });
  }
}
