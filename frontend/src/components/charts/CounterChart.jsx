import { useMemo } from 'react';
import ResponsiveECharts from './ResponsiveECharts.jsx';
import { pick } from '../../lib/rowAccess.js';

const NAME_KEYS  = ['name', 'metric_name', 'variable_name', 'counter_name'];
const VALUE_KEYS = ['value', 'cntr_value'];

/**
 * Generic time-series of named counters. Auto-discovers up to `maxSeries`
 * counter names from the latest snapshot — works across Oracle (v$sysstat),
 * MySQL/MariaDB (SHOW GLOBAL STATUS), and MSSQL (dm_os_performance_counters)
 * because the schema is always (name, value) per row.
 */
export default function CounterChart({ snapshots, title = 'Counters', maxSeries = 6 }) {
  const option = useMemo(
    () => buildOption(snapshots, title, maxSeries),
    [snapshots, title, maxSeries]
  );

  if (!snapshots.length) {
    return <div className="placeholder">No counter data yet.</div>;
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

function buildOption(snapshots, title, maxSeries) {
  const latest = snapshots[snapshots.length - 1];
  const names = (latest.rows || [])
    .map((r) => pick(r, ...NAME_KEYS))
    .filter(Boolean)
    .slice(0, maxSeries);

  const series = names.map((name) => ({
    name,
    type: 'line',
    showSymbol: false,
    smooth: true,
    emphasis: { focus: 'series' },
    data: snapshots.map((snap) => {
      const row = (snap.rows || []).find((r) => pick(r, ...NAME_KEYS) === name);
      const v = row ? pick(row, ...VALUE_KEYS) : null;
      const num = v == null ? null : Number(v);
      return [snap.timestamp, Number.isFinite(num) ? num : null];
    }),
  }));

  return {
    backgroundColor: 'transparent',
    title: { text: title, left: 'left', textStyle: { color: '#e6e9ef', fontSize: 11, fontWeight: 500 } },
    tooltip: { trigger: 'axis' },
    legend: {
      type: 'scroll', top: 2, right: 8,
      itemWidth: 10, itemHeight: 8, itemGap: 10,
      textStyle: { color: '#8a93a6', fontSize: 10 },
    },
    grid: { left: 52, right: 12, top: 28, bottom: 26 },
    xAxis: { type: 'time', axisLine: { lineStyle: { color: '#262c3b' } }, axisLabel: { color: '#8a93a6', fontSize: 10 } },
    yAxis: { type: 'value', axisLine: { lineStyle: { color: '#262c3b' } }, axisLabel: { color: '#8a93a6', fontSize: 10 }, splitLine: { lineStyle: { color: '#262c3b' } } },
    series,
    animation: false,
  };
}
