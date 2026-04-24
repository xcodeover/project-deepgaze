/**
 * Renders the most recent snapshot's rows as a table. Pure presentational.
 * Column set is auto-derived from the row keys so it works for both
 * Oracle (v$sql) and MySQL/MariaDB (events_statements_summary_by_digest)
 * and MSSQL (dm_exec_query_stats) without per-engine code.
 */
export default function TopSqlTable({ snapshot, title = 'Top SQL', onExplain, onInspect }) {
  if (!snapshot || !snapshot.rows?.length) {
    return <div className="placeholder">No top SQL data yet.</div>;
  }

  const cols = Object.keys(snapshot.rows[0]);
  const clickable = typeof onInspect === 'function';

  return (
    <>
      {title && <h3 className="card__title">{title}</h3>}
      <table className={`metric-table ${clickable ? 'metric-table--clickable' : ''}`}>
        <thead>
          <tr>
            {cols.map((c) => <th key={c}>{c}</th>)}
            {onExplain && <th style={{ width: 60 }}></th>}
          </tr>
        </thead>
        <tbody>
          {snapshot.rows.map((row, i) => (
            <tr
              key={i}
              onClick={clickable ? () => onInspect(row) : undefined}
            >
              {cols.map((c) => <td key={c} title={String(row[c] ?? '')}>{format(row[c])}</td>)}
              {onExplain && (
                <td style={{ textAlign: 'right' }}>
                  <button
                    type="button" className="explain-btn"
                    onClick={(e) => { e.stopPropagation(); onExplain(row); }}
                  >Explain</button>
                </td>
              )}
            </tr>
          ))}
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
