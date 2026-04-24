import { useMemo } from 'react';
import ResponsiveECharts from './ResponsiveECharts.jsx';
import { pick } from '../../lib/rowAccess.js';

/**
 * Stacked area of session counts by status over time.
 * Pure presentational — receives raw snapshots as a prop, derives ECharts
 * option via useMemo, and renders. Knows nothing about the store.
 */
export default function SessionsChart({ snapshots, title = 'Sessions by Status' }) {
  const option = useMemo(() => buildOption(snapshots, title), [snapshots, title]);

  if (!snapshots.length) {
    return <div className="placeholder">No session data yet.</div>;
  }

  return (
    <ResponsiveECharts
      option={option}
      style={{ height: '100%', width: '100%' }}
      notMerge
      lazyUpdate
    />
  );
}

function buildOption(snapshots, title) {
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
    title,
    series,
    yAxisName: 'count',
  });
}

function baseOption({ title, series, yAxisName }) {
  return {
    backgroundColor: 'transparent',
    title: { text: title, left: 'left', textStyle: { color: '#e6e9ef', fontSize: 11, fontWeight: 500 } },
    tooltip: { trigger: 'axis' },
    legend: {
      type: 'scroll', top: 2, right: 8,
      itemWidth: 10, itemHeight: 8, itemGap: 10,
      textStyle: { color: '#8a93a6', fontSize: 10 },
    },
    grid: { left: 44, right: 12, top: 28, bottom: 26 },
    xAxis: { type: 'time', axisLine: { lineStyle: { color: '#262c3b' } }, axisLabel: { color: '#8a93a6', fontSize: 10 } },
    yAxis: { type: 'value', name: yAxisName, nameTextStyle: { color: '#8a93a6', fontSize: 10 }, axisLine: { lineStyle: { color: '#262c3b' } }, axisLabel: { color: '#8a93a6', fontSize: 10 }, splitLine: { lineStyle: { color: '#262c3b' } } },
    series,
    animation: false,
  };
}
