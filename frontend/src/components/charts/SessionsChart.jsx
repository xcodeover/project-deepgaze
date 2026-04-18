import { useMemo } from 'react';
import ReactECharts from 'echarts-for-react';
import { pick } from '../../lib/rowAccess.js';

/**
 * Stacked area of session counts by status over time.
 * Pure presentational — receives raw snapshots as a prop, derives ECharts
 * option via useMemo, and renders. Knows nothing about the store.
 */
export default function SessionsChart({ snapshots }) {
  const option = useMemo(() => buildOption(snapshots), [snapshots]);

  if (!snapshots.length) {
    return <div className="placeholder">No session data yet.</div>;
  }

  return (
    <ReactECharts
      option={option}
      style={{ height: 280 }}
      notMerge
      lazyUpdate
    />
  );
}

function buildOption(snapshots) {
  const statusSet = new Set();
  const points = snapshots.map((snap) => {
    const byStatus = {};
    for (const r of snap.rows || []) {
      const status = pick(r, 'status') ?? 'UNKNOWN';
      const cnt    = Number(pick(r, 'cnt') ?? 0);
      byStatus[status] = (byStatus[status] || 0) + cnt;
      statusSet.add(status);
    }
    return { t: snap.timestamp, byStatus };
  });

  const series = Array.from(statusSet).map((status) => ({
    name: status,
    type: 'line',
    stack: 'sessions',
    showSymbol: false,
    smooth: true,
    areaStyle: {},
    emphasis: { focus: 'series' },
    data: points.map((p) => [p.t, p.byStatus[status] || 0]),
  }));

  return baseOption({
    title: 'Sessions by Status',
    series,
    yAxisName: 'count',
  });
}

function baseOption({ title, series, yAxisName }) {
  return {
    backgroundColor: 'transparent',
    title: { text: title, left: 'left', textStyle: { color: '#e6e9ef', fontSize: 13, fontWeight: 500 } },
    tooltip: { trigger: 'axis' },
    legend: { type: 'scroll', bottom: 0, textStyle: { color: '#8a93a6' } },
    grid: { left: 50, right: 20, top: 40, bottom: 40 },
    xAxis: { type: 'time', axisLine: { lineStyle: { color: '#262c3b' } }, axisLabel: { color: '#8a93a6' } },
    yAxis: { type: 'value', name: yAxisName, axisLine: { lineStyle: { color: '#262c3b' } }, axisLabel: { color: '#8a93a6' }, splitLine: { lineStyle: { color: '#262c3b' } } },
    series,
    animation: false,
  };
}
