/**
 * Top-N non-Sleep sessions with clickable rows. Click → open the detail
 * drawer for drill-down + EXPLAIN. Purely presentational; parent owns the
 * selection state so the drawer can sit outside the table's card.
 *
 * Column set is curated rather than derived from row keys — the processlist
 * snapshot has fixed columns we want in a specific order, and the last one
 * (info) needs the full SQL on hover so operators can read past the 200-char
 * truncation before deciding to click.
 *
 * If `onKill` is provided, a trailing "Kill" column is rendered. The button
 * calls `stopPropagation` so it doesn't also trigger the row-click drawer;
 * Sleep sessions don't get a kill action because the frontend elides them
 * from the snapshot anyway, and rows without a numeric PID are skipped
 * defensively.
 */
const COLS = [
  { key: 'id',        label: 'PID',      align: 'right' },
  { key: 'user',      label: 'User' },
  { key: 'host',      label: 'Host' },
  { key: 'db',        label: 'DB' },
  { key: 'command',   label: 'Command' },
  { key: 'time_secs', label: 'Time (s)', align: 'right' },
  { key: 'state',     label: 'State' },
  { key: 'info',      label: 'SQL' },
];

export default function ProcesslistTable({ snapshot, onSelect, selectedId, onKill, onInspect }) {
  if (!snapshot || !snapshot.rows?.length) {
    return (
      <>
        <h3 className="card__title">Active Sessions</h3>
        <div className="placeholder">No active sessions.</div>
      </>
    );
  }

  const showKill    = typeof onKill === 'function';
  const showInspect = typeof onInspect === 'function';

  return (
    <>
      <h3 className="card__title">Active Sessions — click a row for detail</h3>
      <table className="metric-table metric-table--clickable">
        <thead>
          <tr>
            {COLS.map((c) => (
              <th key={c.key} style={c.align ? { textAlign: c.align } : undefined}>{c.label}</th>
            ))}
            {(showKill || showInspect) && (
              <th style={{ textAlign: 'right', width: showKill && showInspect ? 120 : 64 }}>Act.</th>
            )}
          </tr>
        </thead>
        <tbody>
          {snapshot.rows.map((row) => {
            const active = selectedId != null && String(selectedId) === String(row.id);
            return (
              <tr
                key={row.id}
                className={active ? 'row--selected' : undefined}
                onClick={() => onSelect?.(row)}
              >
                {COLS.map((c) => (
                  <td
                    key={c.key}
                    title={String(row[c.key] ?? '')}
                    style={c.align ? { textAlign: c.align } : undefined}
                  >
                    {format(row[c.key])}
                  </td>
                ))}
                {(showKill || showInspect) && (
                  <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                    {showInspect && (
                      <button
                        type="button"
                        className="inspect-btn"
                        title="Show raw row fields"
                        onClick={(e) => { e.stopPropagation(); onInspect(row); }}
                      >Inspect</button>
                    )}
                    {showKill && row.id != null && (
                      <button
                        type="button"
                        className="kill-btn"
                        title={`Kill PID ${row.id}`}
                        onClick={(e) => { e.stopPropagation(); onKill(row); }}
                      >Kill</button>
                    )}
                  </td>
                )}
              </tr>
            );
          })}
        </tbody>
      </table>
    </>
  );
}

function format(v) {
  if (v == null) return '';
  if (typeof v === 'number') return v.toLocaleString();
  return String(v);
}
