import { useState } from 'react';
import TopSqlTable from './TopSqlTable.jsx';
import SlowQueriesTable from './SlowQueriesTable.jsx';

/**
 * Tabbed tile combining the two digest views we care about:
 *   - By Wait      → cumulative SUM_TIMER_WAIT (existing Top SQL behaviour)
 *   - Slow Queries → AVG_TIMER_WAIT with scan-ratio, catches unindexed scans
 *
 * Keeps the 3×3 NOC grid intact by sharing a single tile between the two
 * views instead of adding a 10th tile.
 */
export default function QueryPerformanceView({ byWaitSnapshot, slowSnapshot, onExplain, onInspect }) {
  const [tab, setTab] = useState('byWait');

  return (
    <div className="qp">
      <div className="qp__header">
        <div className="view-toggle" role="tablist" aria-label="Query view">
          <button
            type="button"
            role="tab"
            aria-selected={tab === 'byWait'}
            className={`toggle-btn ${tab === 'byWait' ? 'toggle-btn--active' : ''}`}
            onClick={() => setTab('byWait')}
          >By Wait</button>
          <button
            type="button"
            role="tab"
            aria-selected={tab === 'slow'}
            className={`toggle-btn ${tab === 'slow' ? 'toggle-btn--active' : ''}`}
            onClick={() => setTab('slow')}
          >Slow Queries</button>
        </div>
      </div>
      <div className="qp__body">
        {tab === 'byWait'
          ? <TopSqlTable snapshot={byWaitSnapshot} title={null} onExplain={onExplain} onInspect={onInspect} />
          : <SlowQueriesTable snapshot={slowSnapshot} onExplain={onExplain} onInspect={onInspect} />
        }
      </div>
    </div>
  );
}
