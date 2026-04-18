import { useEffect, useState } from 'react';
import { useMetricStream } from '../hooks/useMetricStream.js';
import {
  useMetricsStore,
  selectStatus,
  selectTargetIds,
  selectGroup,
} from '../store/metricsStore.js';

import ConnectionStatus from './ConnectionStatus.jsx';
import TargetSelector from './TargetSelector.jsx';
import SessionsChart from './charts/SessionsChart.jsx';
import CounterChart from './charts/CounterChart.jsx';
import TopSqlTable from './tables/TopSqlTable.jsx';

export default function Dashboard() {
  useMetricStream();

  const status  = useMetricsStore(selectStatus);
  const targets = useMetricsStore(selectTargetIds);
  const [selected, setSelected] = useState(null);

  useEffect(() => {
    if (!selected && targets.length > 0) setSelected(targets[0]);
  }, [targets, selected]);

  return (
    <div className="app">
      <header className="app__header">
        <div className="app__title">
          DeepGaze
          <small>Agentless DB Monitoring</small>
        </div>
        <ConnectionStatus />
      </header>

      <div className="app__body">
        <TargetSelector
          targets={targets}
          selected={selected}
          onSelect={setSelected}
        />
        <main className="main">
          {selected
            ? <TargetView targetId={selected} />
            : <EmptyState status={status} hasTargets={targets.length > 0} />
          }
        </main>
      </div>
    </div>
  );
}

function EmptyState({ status, hasTargets }) {
  const message =
    status === 'live' && !hasTargets ? 'Connected — waiting for first metric snapshot from the backend…' :
    status === 'live'                ? 'Select a target on the left to view metrics.' :
    status === 'connecting'          ? 'Opening SSE stream to /api/stream/metrics…' :
    status === 'reconnecting'        ? 'Stream interrupted — reconnecting…' :
    status === 'disconnected'        ? 'Disconnected from backend. Check that the Spring Boot server is running on :8080.' :
                                       'Initialising…';
  return (
    <div className="card card--full">
      <h3 className="card__title">Status</h3>
      <div className="placeholder">{message}</div>
    </div>
  );
}

/**
 * Reads the slices of store relevant to a target and passes raw arrays
 * down to the dumb chart/table components.
 */
function TargetView({ targetId }) {
  const sessions   = useMetricsStore(selectGroup(targetId, 'sessions'));
  const sysstat    = useMetricsStore(selectGroup(targetId, 'sysstat'));
  const status     = useMetricsStore(selectGroup(targetId, 'status'));
  const perfCnt    = useMetricsStore(selectGroup(targetId, 'perfCounters'));
  const topSql     = useMetricsStore(selectGroup(targetId, 'topSql'));
  const topDigests = useMetricsStore(selectGroup(targetId, 'topDigests'));

  // Pick the counter feed appropriate for this engine without per-engine code:
  // Oracle exposes "sysstat", MySQL/MariaDB "status", MSSQL "perfCounters".
  const counterSnapshots =
    sysstat.length ? sysstat :
    status.length  ? status  :
    perfCnt;

  const topSqlSnapshot = (topSql.at(-1)) || (topDigests.at(-1)) || null;

  return (
    <>
      <div className="card">
        <SessionsChart snapshots={sessions} />
      </div>
      <div className="card">
        <CounterChart snapshots={counterSnapshots} title="Key Counters" />
      </div>
      <div className="card card--full">
        <TopSqlTable snapshot={topSqlSnapshot} />
      </div>
    </>
  );
}
