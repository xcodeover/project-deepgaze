import { useMemo } from 'react';
import ResponsiveECharts from './ResponsiveECharts.jsx';
import { pick } from '../../lib/rowAccess.js';

/**
 * Horizontal bar of the top N wait events by per-tick delta of
 * SUM_TIMER_WAIT. Backend emits cumulative counters; the delta between the
 * two most recent snapshots is what reflects "work happening right now."
 *
 * Picosecond units (performance_schema convention) are converted to
 * milliseconds for display.
 */
export default function TopWaitsChart({ snapshots, title = 'Top Wait Events (Δ / tick, ms)', topN = 10 }) {
  const option = useMemo(() => buildOption(snapshots, title, topN), [snapshots, title, topN]);

  if (snapshots.length < 2) {
    return <div className="placeholder">Collecting baseline for wait-event deltas…</div>;
  }
  return <ResponsiveECharts option={option} style={{ height: '100%', width: '100%' }} notMerge lazyUpdate />;
}

function buildOption(snapshots, title, topN) {
  // useMemo runs on every render, before the <2-snapshot gate in the
  // component body — so guard here too, or snapshots[-1].rows would throw.
  if (snapshots.length < 2) return null;

  const latest = snapshots[snapshots.length - 1];
  const prev   = snapshots[snapshots.length - 2];

  const prevByName = new Map();
  for (const r of prev.rows || []) {
    prevByName.set(pick(r, 'event_name'), Number(pick(r, 'sum_timer_wait') ?? 0));
  }

  const deltas = (latest.rows || [])
    .map((r) => {
      const name = pick(r, 'event_name');
      const nowV = Number(pick(r, 'sum_timer_wait') ?? 0);
      const prevV = prevByName.get(name) ?? nowV;
      const deltaPs = Math.max(0, nowV - prevV);
      return { name, ms: deltaPs / 1e9 };
    })
    .filter((d) => d.name && d.ms > 0)
    .sort((a, b) => b.ms - a.ms)
    .slice(0, topN)
    .reverse();

  // Reserve a title strip only when one was actually passed in — the BentoTile
  // header already labels the panel, so the default caller passes "" and we
  // use the extra vertical space for bars instead of an empty header band.
  const hasTitle = typeof title === 'string' && title.trim().length > 0;

  return {
    backgroundColor: 'transparent',
    ...(hasTitle ? { title: { text: title, left: 'left', textStyle: { color: '#e6e9ef', fontSize: 11, fontWeight: 500 } } } : {}),
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      valueFormatter: (v) => `${formatMs(Number(v))} ms`,
    },
    // containLabel lets ECharts size the left gutter to whatever the longest
    // y-axis label needs after truncation — replaces the old hardcoded 180px
    // which ate half the tile on narrow layouts.
    grid: {
      left: 4,
      right: 48,
      top: hasTitle ? 24 : 6,
      bottom: 22,
      containLabel: true,
    },
    xAxis: {
      type: 'value',
      axisLine: { lineStyle: { color: '#262c3b' } },
      axisLabel: { color: '#8a93a6', fontSize: 10, formatter: (v) => formatMs(v) },
      splitLine: { lineStyle: { color: '#262c3b' } },
    },
    yAxis: {
      type: 'category',
      // Keep the full raw name in the data so the tooltip shows the unambiguous
      // performance_schema identifier; the axisLabel formatter is purely for
      // display compactness.
      data: deltas.map((d) => d.name),
      axisLine: { lineStyle: { color: '#262c3b' } },
      axisTick: { show: false },
      axisLabel: {
        color: '#8a93a6',
        fontSize: 10,
        width: 150,
        overflow: 'truncate',
        formatter: (name) => shortenWaitName(name),
      },
    },
    series: [{
      type: 'bar',
      data: deltas.map((d) => Number(d.ms.toFixed(3))),
      itemStyle: { color: '#4ea1ff', borderRadius: [0, 3, 3, 0] },
      barMaxWidth: 14,
      label: {
        show: true,
        position: 'right',
        color: '#e6e9ef',
        fontSize: 10,
        formatter: (p) => formatMs(p.value),
      },
    }],
    animation: false,
  };
}

// Strip the performance_schema `wait/` prefix and drop the redundant `sql/`
// segment that MariaDB repeats on almost every event — buys ~30 chars of
// display width with no loss of information that isn't already in the tooltip.
function shortenWaitName(name) {
  if (!name) return '';
  return String(name)
    .replace(/^wait\//, '')
    .replace(/\/sql\//g, '/');
}

function formatMs(v) {
  const n = Number(v);
  if (!Number.isFinite(n)) return '';
  if (n < 1)    return n.toFixed(2);
  if (n < 100)  return n.toFixed(1);
  return Math.round(n).toLocaleString();
}
