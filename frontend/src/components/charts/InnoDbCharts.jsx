import { useMemo } from 'react';
import ResponsiveECharts from './ResponsiveECharts.jsx';
import { pick } from '../../lib/rowAccess.js';

/**
 * Two derived InnoDB views computed from the same `status` snapshot stream
 * feeding Key Counters. No extra backend query — rates/ratios are produced
 * on the fly from adjacent snapshots.
 *
 *   InnoDbHealthChart     — buffer-pool hit ratio % and dirty-page %
 *   InnoDbThroughputChart — rows read/inserted/updated/deleted per second
 */

const NAME_KEYS  = ['variable_name', 'name'];
const VALUE_KEYS = ['value'];

function getVar(rows, name) {
  const want = name.toUpperCase();
  for (const r of rows || []) {
    const n = String(pick(r, ...NAME_KEYS) ?? '').toUpperCase();
    if (n === want) return Number(pick(r, ...VALUE_KEYS) ?? 0);
  }
  return 0;
}

export function InnoDbHealthChart({ snapshots }) {
  const option = useMemo(() => buildHealth(snapshots), [snapshots]);
  if (snapshots.length < 2) {
    return <div className="placeholder">Collecting baseline for InnoDB health…</div>;
  }
  return <ResponsiveECharts option={option} style={{ height: '100%', width: '100%' }} notMerge lazyUpdate />;
}

export function InnoDbThroughputChart({ snapshots }) {
  const option = useMemo(() => buildThroughput(snapshots), [snapshots]);
  if (snapshots.length < 2) {
    return <div className="placeholder">Collecting baseline for InnoDB throughput…</div>;
  }
  return <ResponsiveECharts option={option} style={{ height: '100%', width: '100%' }} notMerge lazyUpdate />;
}

function buildHealth(snapshots) {
  if (snapshots.length < 2) return null;

  const hitRatio = [];
  const dirtyPct = [];
  for (let i = 1; i < snapshots.length; i++) {
    const prev = snapshots[i - 1];
    const curr = snapshots[i];

    const dReq = getVar(curr.rows, 'Innodb_buffer_pool_read_requests')
               - getVar(prev.rows, 'Innodb_buffer_pool_read_requests');
    const dRd  = getVar(curr.rows, 'Innodb_buffer_pool_reads')
               - getVar(prev.rows, 'Innodb_buffer_pool_reads');
    const hr = dReq > 0 ? clamp((1 - dRd / dReq) * 100, 0, 100) : null;

    const dirty = getVar(curr.rows, 'Innodb_buffer_pool_pages_dirty');
    const total = getVar(curr.rows, 'Innodb_buffer_pool_pages_total');
    const dp = total > 0 ? (dirty / total) * 100 : null;

    hitRatio.push([curr.timestamp, hr]);
    dirtyPct.push([curr.timestamp, dp]);
  }

  return baseOption({
    title: 'InnoDB Health',
    yAxisName: '%',
    max: 100,
    series: [
      { name: 'Buffer Pool Hit Ratio', type: 'line', showSymbol: false, smooth: true, data: hitRatio, itemStyle: { color: '#2dd4a4' } },
      { name: 'Dirty Pages',           type: 'line', showSymbol: false, smooth: true, data: dirtyPct, itemStyle: { color: '#f5b431' } },
    ],
  });
}

function buildThroughput(snapshots) {
  if (snapshots.length < 2) return null;

  const metrics = [
    { key: 'Innodb_rows_read',     label: 'Read/s',   color: '#4ea1ff' },
    { key: 'Innodb_rows_inserted', label: 'Insert/s', color: '#2dd4a4' },
    { key: 'Innodb_rows_updated',  label: 'Update/s', color: '#f5b431' },
    { key: 'Innodb_rows_deleted',  label: 'Delete/s', color: '#ef5a5a' },
  ];
  const series = metrics.map((m) => ({
    name: m.label,
    type: 'line',
    showSymbol: false,
    smooth: true,
    data: [],
    itemStyle: { color: m.color },
  }));

  for (let i = 1; i < snapshots.length; i++) {
    const prev = snapshots[i - 1];
    const curr = snapshots[i];
    const dt = (new Date(curr.timestamp) - new Date(prev.timestamp)) / 1000;
    if (dt <= 0) continue;
    metrics.forEach((m, idx) => {
      const delta = getVar(curr.rows, m.key) - getVar(prev.rows, m.key);
      series[idx].data.push([curr.timestamp, Math.max(0, delta / dt)]);
    });
  }

  return baseOption({
    title: 'InnoDB Throughput',
    yAxisName: 'rows/s',
    series,
  });
}

function clamp(v, lo, hi) {
  return Math.max(lo, Math.min(hi, v));
}

function baseOption({ title, yAxisName, series, max }) {
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
    yAxis: { type: 'value', name: yAxisName, max, nameTextStyle: { color: '#8a93a6', fontSize: 10 }, axisLine: { lineStyle: { color: '#262c3b' } }, axisLabel: { color: '#8a93a6', fontSize: 10 }, splitLine: { lineStyle: { color: '#262c3b' } } },
    series,
    animation: false,
  };
}
