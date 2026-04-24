import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { navigate } from '../lib/router.js';
import {
  useMetricsStore,
  selectStatus,
  selectTargetNames,
  selectGroup,
  selectLatestLive,
  selectReplayState,
} from '../store/metricsStore.js';
import {
  useTargetsStore,
  selectTargetOrder,
  selectTargetsById,
} from '../store/targetsStore.js';

import ConnectionStatus from './ConnectionStatus.jsx';
import UserChip from './UserChip.jsx';
import AlertBadge from './AlertBadge.jsx';
import AlertToastHost from './AlertToastHost.jsx';
import AlertHistoryPanel from './AlertHistoryPanel.jsx';
import FiringAlertsCard from './FiringAlertsCard.jsx';
import HealthBanner from './HealthBanner.jsx';
import TargetSelector from './TargetSelector.jsx';
import BentoTile from './BentoTile.jsx';
import TopWaitsChart from './charts/TopWaitsChart.jsx';
import ActiveSessionTrend from './charts/ActiveSessionTrend.jsx';
import QueryPerformanceView from './tables/QueryPerformanceView.jsx';
import BlockingView from './tables/BlockingView.jsx';
import ProcesslistTable from './tables/ProcesslistTable.jsx';
import SessionDetailPanel from './SessionDetailPanel.jsx';
import KillConfirmModal from './KillConfirmModal.jsx';
import ExplainModal from './ExplainModal.jsx';
import RowDetailModal from './RowDetailModal.jsx';
import BusinessScoreboard from './BusinessScoreboard.jsx';
import RatiosTile from './RatiosTile.jsx';
import SaturationTile from './SaturationTile.jsx';
import StorageSaturationTile from './StorageSaturationTile.jsx';
import InfrastructureHub from './InfrastructureHub.jsx';
import TimeMachineBar from './TimeMachineBar.jsx';
import QueryHistoryPanel from './QueryHistoryPanel.jsx';

const RANGES = [
  { id: '5m',  label: '5m',  seconds:  5 * 60 },
  { id: '15m', label: '15m', seconds: 15 * 60 },
  { id: '1h',  label: '1h',  seconds: 60 * 60 },
];

export default function Dashboard({ targetId }) {
  const status  = useMetricsStore(selectStatus);
  const targets = useTargetsStore(selectTargetOrder);
  const byId    = useTargetsStore(selectTargetsById);
  const targetsStatus = useTargetsStore((s) => s.status);
  const streamNames = useMetricsStore(selectTargetNames);
  const enterReplay = useMetricsStore((s) => s.enterReplay);
  const [rangeId, setRangeId]   = useState('5m');
  const [historyOpen, setHistoryOpen] = useState(false);
  const [queryHistoryOpen, setQueryHistoryOpen] = useState(false);
  const qhAutoOpenedRef = useRef(false);

  // Shared-link bootstrap: when a teammate opens `?qh=1` for a configured
  // target, auto-open the Query History modal once. The panel itself hydrates
  // its filter state from the same query string. Gate on `qhAutoOpenedRef` so
  // we don't re-open after the user explicitly closes the modal during the
  // same session.
  useEffect(() => {
    if (qhAutoOpenedRef.current) return;
    if (typeof window === 'undefined') return;
    const params = new URLSearchParams(window.location.search);
    if (params.get('qh') !== '1') return;
    if (!targetId || !byId[targetId]) return;
    qhAutoOpenedRef.current = true;
    setQueryHistoryOpen(true);
  }, [targetId, byId]);

  // Prefer the configured displayName over the live stream's targetName — the
  // configured value is what the operator sees on the Settings page, so the
  // sidebar should mirror it. Fall back to the stream for targets that haven't
  // made it into the configured list yet (race during a fresh bootstrap).
  const labels = useMemo(() => {
    const out = { ...streamNames };
    for (const id of targets) {
      const t = byId[id];
      if (t?.displayName) out[id] = t.displayName;
    }
    return out;
  }, [targets, byId, streamNames]);

  // URL says "show this target" but the id isn't configured — bounce back to
  // the fleet view rather than rendering an empty bento. Wait until the
  // targets store has finished its initial load so we don't redirect during
  // the cold-start race.
  useEffect(() => {
    if (targetsStatus !== 'ready') return;
    if (!targetId) return;
    if (!byId[targetId]) navigate('/');
  }, [targetId, byId, targetsStatus]);

  // Alert-to-Replay bridge: switch target if needed, then enter replay at the
  // alert's timestamp. Closing the drawer lets the user see the Bento tiles
  // and TimeMachineBar snap to the moment the rule fired.
  const handleJumpToAlert = useCallback((ev) => {
    if (!ev?.targetId) return;
    const ts = Date.parse(ev.timestamp);
    if (Number.isNaN(ts)) return;
    if (ev.targetId !== targetId) navigate(`/targets/${encodeURIComponent(ev.targetId)}`);
    enterReplay(ev.targetId, ts);
    setHistoryOpen(false);
  }, [enterReplay, targetId]);

  const validTargetId = targetId && byId[targetId] ? targetId : null;

  return (
    <div className="app">
      <header className="app__header">
        <div className="app__title">
          DeepGaze
          <small>Agentless DB Monitoring</small>
        </div>
        <div className="app__header-right">
          <AlertBadge open={historyOpen} onToggle={() => setHistoryOpen((v) => !v)} />
          <RangeToggle value={rangeId} onChange={setRangeId} />
          <ConnectionStatus />
          {validTargetId && (
            <button
              type="button"
              className="tgt-btn tgt-btn--ghost"
              onClick={() => setQueryHistoryOpen(true)}
              title="Search past queries"
            >🔎 Query History</button>
          )}
          <button
            type="button"
            className="tgt-btn tgt-btn--ghost"
            onClick={() => navigate('/settings')}
            title="Settings"
          >⚙️ Settings</button>
          <UserChip />
        </div>
      </header>

      <AlertToastHost />
      <AlertHistoryPanel
        open={historyOpen}
        onClose={() => setHistoryOpen(false)}
        onJumpToAlert={handleJumpToAlert}
      />
      <QueryHistoryPanel
        open={queryHistoryOpen && !!validTargetId}
        onClose={() => setQueryHistoryOpen(false)}
        targetId={validTargetId}
        targetName={validTargetId ? labels[validTargetId] : null}
      />

      <div className="app__body">
        <TargetSelector
          targets={targets}
          labels={labels}
          selected={validTargetId}
        />
        <main className="main">
          {validTargetId
            ? <TargetView targetId={validTargetId} targetName={labels[validTargetId]} rangeId={rangeId} />
            : <EmptyState status={status} hasTargets={targets.length > 0} />
          }
        </main>
      </div>
    </div>
  );
}

function RangeToggle({ value, onChange }) {
  return (
    <div className="view-toggle" role="tablist" aria-label="Time range">
      {RANGES.map((r) => (
        <button
          key={r.id}
          type="button"
          role="tab"
          aria-selected={value === r.id}
          className={`toggle-btn ${value === r.id ? 'toggle-btn--active' : ''}`}
          onClick={() => onChange(r.id)}
        >{r.label}</button>
      ))}
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

function windowSlice(snapshots, rangeSeconds) {
  if (snapshots.length === 0) return snapshots;
  const last = snapshots[snapshots.length - 1];
  const endMs = Date.parse(last.timestamp);
  const cutoffMs = endMs - rangeSeconds * 1000;
  let i = 0;
  while (i < snapshots.length && Date.parse(snapshots[i].timestamp) < cutoffMs) i++;
  return i === 0 ? snapshots : snapshots.slice(i);
}

/**
 * Renders the 9-slot bento grid. Every tile always occupies its grid-area
 * even when data is absent — BentoTile's skeleton states keep the layout
 * stable across engine switches (MariaDB / Oracle / MSSQL).
 */
function TargetView({ targetId, targetName, rangeId }) {
  const activeSessionsRaw = useMetricsStore(selectGroup(targetId, 'activeSessions'));
  const topWaitsRaw       = useMetricsStore(selectGroup(targetId, 'topWaits'));
  const statusRaw         = useMetricsStore(selectGroup(targetId, 'status'));
  const blockers          = useMetricsStore(selectGroup(targetId, 'blockers'));
  const topSql            = useMetricsStore(selectGroup(targetId, 'topSql'));
  const topDigests        = useMetricsStore(selectGroup(targetId, 'topDigests'));
  const slowQueries       = useMetricsStore(selectGroup(targetId, 'slowQueries'));
  const processlist       = useMetricsStore(selectGroup(targetId, 'processlist'));
  const businessMetrics   = useMetricsStore(selectGroup(targetId, 'businessMetrics'));
  const hostCpu           = useMetricsStore(selectGroup(targetId, 'hostCpu'));
  const hostMemory        = useMetricsStore(selectGroup(targetId, 'hostMemory'));
  const hostDisk          = useMetricsStore(selectGroup(targetId, 'hostDisk'));
  const hostFilesystem    = useMetricsStore(selectGroup(targetId, 'hostFilesystem'));
  const dbSaturation      = useMetricsStore(selectGroup(targetId, 'dbSaturation'));
  const ratios            = useMetricsStore(selectGroup(targetId, 'ratios'));

  // Performance Diff inputs — the live shadow is only meaningful when replay
  // is active, but the selector returns a stable fallback either way.
  const replay            = useMetricsStore(selectReplayState);
  const liveRatios        = useMetricsStore(selectLatestLive(targetId, 'ratios'));
  const liveSaturation    = useMetricsStore(selectLatestLive(targetId, 'dbSaturation'));
  const replayActive      = replay.active && replay.targetId === targetId;

  const [selectedSession, setSelectedSession] = useState(null);
  const [killTarget, setKillTarget]           = useState(null);
  const [explainTarget, setExplainTarget]     = useState(null);
  const [inspectRow, setInspectRow]           = useState(null);
  useEffect(() => {
    setSelectedSession(null);
    setKillTarget(null);
    setExplainTarget(null);
    setInspectRow(null);
  }, [targetId]);

  const rangeSeconds = RANGES.find((r) => r.id === rangeId)?.seconds ?? 300;

  const activeSessions = useMemo(() => windowSlice(activeSessionsRaw, rangeSeconds), [activeSessionsRaw, rangeSeconds]);
  const topWaits       = useMemo(() => windowSlice(topWaitsRaw,       rangeSeconds), [topWaitsRaw,       rangeSeconds]);
  const statusRows     = useMemo(() => windowSlice(statusRaw,         rangeSeconds), [statusRaw,         rangeSeconds]);
  const cpuSnaps       = useMemo(() => windowSlice(hostCpu,           rangeSeconds), [hostCpu,           rangeSeconds]);

  const topSqlSnapshot      = (topSql.at(-1)) || (topDigests.at(-1)) || null;
  const slowQueriesSnapshot = slowQueries.at(-1) || null;
  const blockersSnapshot    = blockers.at(-1) || null;
  const processlistSnapshot = processlist.at(-1) || null;
  const scoreboardSnapshot  = businessMetrics.at(-1) || null;

  const hasKpis   = scoreboardSnapshot && scoreboardSnapshot.rows?.length > 0;
  const hasRatios = ratios.length > 0 && (ratios.at(-1)?.rows?.length ?? 0) > 0;
  const hasSat    = dbSaturation.length > 0;
  const hasAsh    = activeSessions.length > 0;
  const hasWaits  = topWaits.length > 0;
  const hasInfra  = cpuSnaps.length > 0 || hostMemory.length > 0 || statusRows.length > 0;
  const hasPl     = !!processlistSnapshot;
  const hasLocks  = !!blockersSnapshot;
  const hasSql    = !!topSqlSnapshot || !!slowQueriesSnapshot;

  return (
    <>
      <HealthBanner targetId={targetId} targetName={targetName} />
      <FiringAlertsCard />
      <div className="bento">
        <BentoTile area="kpis" title="Business KPIs"
          status={hasKpis ? 'ok' : 'empty'}
          statusHint="No business KPIs configured for this target.">
          <BusinessScoreboard snapshot={scoreboardSnapshot} />
        </BentoTile>

        <BentoTile area="ratios" title="Cache & Buffer Ratios"
          status={hasRatios ? 'ok' : 'loading'}
          statusHint="Collecting buffer-cache ratios…">
          <RatiosTile
            snapshot={ratios.at(-1)}
            liveSnapshot={liveRatios}
            replayActive={replayActive}
          />
        </BentoTile>

        <BentoTile area="saturation" title="System Saturation"
          status={hasSat ? 'ok' : 'loading'}
          statusHint="Awaiting saturation snapshot…">
          <SaturationTile
            saturationSnapshots={dbSaturation}
            liveSnapshot={liveSaturation}
            replayActive={replayActive}
          />
        </BentoTile>

        <BentoTile area="ash" title="Active Sessions by Wait Class"
          subtitle="rolling 60 ticks"
          status={hasAsh ? 'ok' : 'loading'}
          statusHint="Building ASH baseline…">
          <ActiveSessionTrend snapshots={activeSessions} />
        </BentoTile>

        <BentoTile area="waits" title="Top Wait Events"
          status={hasWaits ? 'ok' : 'loading'}>
          <TopWaitsChart snapshots={topWaits} title="" />
        </BentoTile>

        <BentoTile area="infra"
          status={hasInfra ? 'ok' : 'loading'}
          statusHint="Opening host-exporter channel…">
          <InfrastructureHub
            cpuSnapshots={cpuSnaps}
            memSnapshots={hostMemory}
            diskSnapshots={hostDisk}
            statusSnapshots={statusRows}
            fsSnapshots={hostFilesystem}
            saturationSnapshots={dbSaturation}
          />
        </BentoTile>

        <BentoTile area="processlist" title="Processlist" scroll
          status={hasPl ? 'ok' : 'empty'} statusHint="No active sessions.">
          <ProcesslistTable
            snapshot={processlistSnapshot}
            onSelect={setSelectedSession}
            selectedId={selectedSession?.id}
            onKill={setKillTarget}
            onInspect={(row) => setInspectRow({ row, kind: 'session' })}
          />
        </BentoTile>

        <BentoTile area="locks" title="Lock Tree" scroll
          status={hasLocks ? 'ok' : 'empty'} statusHint="No blocking detected.">
          <BlockingView snapshot={blockersSnapshot} onKill={setKillTarget} />
        </BentoTile>

        <BentoTile area="storage" title="Storage Saturation"
          subtitle="tablespaces · FRA · archive logs (60s)"
          scroll
          status="ok">
          <StorageSaturationTile targetId={targetId} />
        </BentoTile>

        <BentoTile area="queries" title="Top / Slow Queries" scroll
          status={hasSql ? 'ok' : 'empty'} statusHint="No query samples yet.">
          <QueryPerformanceView
            byWaitSnapshot={topSqlSnapshot}
            slowSnapshot={slowQueriesSnapshot}
            onExplain={setExplainTarget}
            onInspect={(row) => setInspectRow({ row, kind: 'query' })}
          />
        </BentoTile>
      </div>

      <TimeMachineBar targetId={targetId} />

      <SessionDetailPanel
        targetId={targetId}
        session={selectedSession}
        onClose={() => setSelectedSession(null)}
      />
      <KillConfirmModal
        open={!!killTarget}
        targetId={targetId}
        session={killTarget}
        onClose={() => setKillTarget(null)}
        onKilled={(pid) => {
          if (selectedSession?.id === pid) setSelectedSession(null);
        }}
      />
      <ExplainModal
        open={!!explainTarget}
        targetId={targetId}
        query={explainTarget}
        onClose={() => setExplainTarget(null)}
      />
      <RowDetailModal
        open={!!inspectRow}
        row={inspectRow?.row}
        title={inspectRow?.kind === 'session' ? 'Session Detail' : 'Query Detail'}
        subtitle={`${targetName || targetId}${replayActive ? ' · replay' : ''}`}
        onClose={() => setInspectRow(null)}
      />
    </>
  );
}
