import { useState } from 'react';
import TopSqlTable from './TopSqlTable.jsx';
import LockTreeView from './LockTreeView.jsx';

/**
 * Card body for the blockers/waiters snapshot. Owns the view-mode toggle and
 * dispatches to either the tree or the flat table. Empty state is handled
 * here once so both children can assume non-empty input.
 */
export default function BlockingView({ snapshot, onKill }) {
  const [mode, setMode] = useState('tree');
  const hasRows = !!(snapshot && snapshot.rows && snapshot.rows.length);

  return (
    <>
      <div className="card__header">
        <h3 className="card__title">Blocking Sessions</h3>
        <div className="view-toggle" role="tablist">
          <button
            type="button"
            role="tab"
            aria-selected={mode === 'tree'}
            className={`toggle-btn ${mode === 'tree' ? 'toggle-btn--active' : ''}`}
            onClick={() => setMode('tree')}
          >Tree</button>
          <button
            type="button"
            role="tab"
            aria-selected={mode === 'table'}
            className={`toggle-btn ${mode === 'table' ? 'toggle-btn--active' : ''}`}
            onClick={() => setMode('table')}
          >Table</button>
        </div>
      </div>
      {!hasRows
        ? <div className="placeholder">No blocked sessions.</div>
        : mode === 'tree'
          ? <LockTreeView rows={snapshot.rows} onKill={onKill} />
          : <TopSqlTable snapshot={snapshot} title={null} />
      }
    </>
  );
}
