import { useMemo } from 'react';
import ResponsiveECharts from './ResponsiveECharts.jsx';
import { pick } from '../../lib/rowAccess.js';

/**
 * ASH-style stacked-area chart of active sessions by wait CLASS over the last
 * ~60 ticks. Purely frontend-derived from the `activeSessions` group already
 * in the store — each snapshot's rows list active sessions at that instant;
 * we bucket them by coarse wait class (CPU / IO / Lock / Network / Other)
 * and stack the per-class counts across time.
 *
 * Wait-class normalisation handles all three engines without backend changes:
 *   MariaDB performance_schema -> 'wait/io/...', 'wait/lock/...', 'wait/synch/...'
 *   Oracle v$session.WAIT_CLASS -> 'User I/O', 'Concurrency', 'Network', 'Idle', ...
 *   MSSQL  sys.dm_exec_requests.wait_type -> 'PAGEIOLATCH_SH', 'LCK_*', 'ASYNC_NETWORK_IO'
 */
const CLASS_ORDER = ['CPU', 'I/O', 'Lock', 'Network', 'Other'];
const CLASS_COLORS = {
  'CPU':     '#4ea1ff',
  'I/O':     '#f5b431',
  'Lock':    '#ef5a5a',
  'Network': '#2dd4a4',
  'Other':   '#6b7280',
};

export default function ActiveSessionTrend({ snapshots }) {
  const tail = useMemo(() => (snapshots || []).slice(-60), [snapshots]);
  const option = useMemo(() => buildOption(tail), [tail]);

  if (tail.length < 2) {
    return <div className="placeholder">Building ASH baseline…</div>;
  }
  return <ResponsiveECharts option={option} style={{ height: '100%', width: '100%' }} notMerge lazyUpdate />;
}

function buildOption(snapshots) {
  const xs = snapshots.map((s) => s.timestamp);
  const counts = snapshots.map((s) => bucketSnapshot(s));

  const series = CLASS_ORDER.map((cls) => ({
    name: cls,
    type: 'line',
    stack: 'ash',
    showSymbol: false,
    smooth: true,
    areaStyle: { opacity: 0.55 },
    lineStyle: { width: 1 },
    itemStyle: { color: CLASS_COLORS[cls] },
    data: xs.map((t, i) => [t, counts[i][cls] || 0]),
  }));

  return {
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis', axisPointer: { type: 'line' } },
    legend: {
      top: 2, right: 8, type: 'scroll',
      itemWidth: 10, itemHeight: 8, itemGap: 10,
      textStyle: { color: '#8a93a6', fontSize: 10 },
    },
    grid: { left: 40, right: 12, top: 26, bottom: 24, containLabel: false },
    xAxis: { type: 'time', axisLine: { lineStyle: { color: '#262c3b' } }, axisLabel: { color: '#8a93a6', fontSize: 10 } },
    yAxis: { type: 'value', minInterval: 1, axisLine: { lineStyle: { color: '#262c3b' } }, axisLabel: { color: '#8a93a6', fontSize: 10 }, splitLine: { lineStyle: { color: '#262c3b' } } },
    series,
    animation: false,
  };
}

function bucketSnapshot(snap) {
  const out = { 'CPU': 0, 'I/O': 0, 'Lock': 0, 'Network': 0, 'Other': 0 };
  const rows = snap?.rows || [];
  for (const r of rows) {
    const raw = pick(r, 'wait_class', 'waitClass', 'wait_type', 'event', 'event_name', 'state');
    const count = pick(r, 'cnt', 'count_star', 'count', 'sessions') ?? 1;
    const n = Number(count);
    const inc = Number.isFinite(n) && n > 0 ? n : 1;
    out[classify(raw)] += inc;
  }
  return out;
}

function classify(raw) {
  if (!raw) return 'CPU';
  const s = String(raw).toLowerCase();
  if (s.includes('idle')) return 'Other';
  if (s === 'on cpu' || s === 'running' || s === 'cpu' || s.endsWith('::cpu')) return 'CPU';
  if (s.includes('lock') || s.startsWith('lck_') || s.includes('latch')) return 'Lock';
  if (s.includes('network') || s.startsWith('async_network') || s.includes('net')) return 'Network';
  if (s.includes('io') || s.startsWith('wait/io') || s.includes('pageio') || s.includes('read') || s.includes('write')) return 'I/O';
  if (s.startsWith('wait/synch') || s.includes('mutex') || s.includes('spin')) return 'Lock';
  return 'Other';
}
