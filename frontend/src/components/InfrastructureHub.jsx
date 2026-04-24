import { useMemo, useState } from 'react';
import ResponsiveECharts from './charts/ResponsiveECharts.jsx';
import { pick } from '../lib/rowAccess.js';

/**
 * "Infrastructure Hub" tile — three layered tabs sharing a single cell:
 *
 *   Host        CPU user/system/iowait stacked over time + memory/swap KPIs
 *   Disk I/O    per-disk throughput / queue / util% table
 *   Engine      MariaDB internals — thread cache hit %, table open cache
 *               misses/s, disk-vs-memory temp-table ratio
 *
 * Designed to fit the 200px tile height. CPU chart uses `height: 100%` so
 * ResponsiveECharts picks up tile resizes; the engine + disk views are
 * compact tables/KPIs that never overflow the cell thanks to the container's
 * internal scroll when the table grows beyond the disk-count height.
 */
export default function InfrastructureHub({
  cpuSnapshots,
  memSnapshots,
  diskSnapshots,
  statusSnapshots,
  fsSnapshots,
  saturationSnapshots,
}) {
  const [tab, setTab] = useState('host');

  return (
    <div className="infra">
      <div className="infra__header">
        <h3 className="card__title">Infrastructure Hub</h3>
        <div className="view-toggle" role="tablist" aria-label="Infrastructure view">
          <TabBtn active={tab === 'host'}    onClick={() => setTab('host')}>Host</TabBtn>
          <TabBtn active={tab === 'disk'}    onClick={() => setTab('disk')}>Disk I/O</TabBtn>
          <TabBtn active={tab === 'storage'} onClick={() => setTab('storage')}>Storage</TabBtn>
          <TabBtn active={tab === 'engine'}  onClick={() => setTab('engine')}>Engine</TabBtn>
        </div>
      </div>
      <div className="infra__body">
        {tab === 'host'    && <HostPanel cpuSnapshots={cpuSnapshots} memSnapshots={memSnapshots} />}
        {tab === 'disk'    && <DiskPanel diskSnapshots={diskSnapshots} />}
        {tab === 'storage' && <StoragePanel fsSnapshots={fsSnapshots} />}
        {tab === 'engine'  && <EnginePanel statusSnapshots={statusSnapshots} saturationSnapshots={saturationSnapshots} />}
      </div>
    </div>
  );
}

function TabBtn({ active, onClick, children }) {
  return (
    <button
      type="button"
      role="tab"
      aria-selected={active}
      className={`toggle-btn ${active ? 'toggle-btn--active' : ''}`}
      onClick={onClick}
    >{children}</button>
  );
}

/* ------------------------------- HOST -------------------------------- */

function HostPanel({ cpuSnapshots, memSnapshots }) {
  const option = useMemo(() => buildCpuOption(cpuSnapshots), [cpuSnapshots]);
  const latestMem = memSnapshots.at(-1)?.rows?.[0] ?? null;

  if (!cpuSnapshots.length && !latestMem) {
    return <div className="placeholder">Collecting host metrics…</div>;
  }

  return (
    <div className="infra-host">
      <div className="infra-host__chart">
        {cpuSnapshots.length >= 2
          ? <ResponsiveECharts option={option} style={{ height: '100%', width: '100%' }} notMerge lazyUpdate />
          : <div className="placeholder">Collecting CPU baseline…</div>}
      </div>
      <div className="infra-host__stats">
        <MiniKpi label="Memory" value={latestMem ? `${fmtPct(latestMem.used_pct)}%` : '—'}
                 sub={latestMem ? `${fmtMb(latestMem.used_mb)} / ${fmtMb(latestMem.total_mb)}` : ''} />
        <MiniKpi label="Swap"   value={latestMem ? `${fmtPct(latestMem.swap_used_pct)}%` : '—'}
                 sub={latestMem ? `${fmtMb(latestMem.swap_used_mb)} / ${fmtMb(latestMem.swap_total_mb)}` : ''}
                 warn={latestMem && Number(latestMem.swap_used_pct) > 0.5} />
      </div>
    </div>
  );
}

function MiniKpi({ label, value, sub, warn }) {
  return (
    <div className={`mini-kpi ${warn ? 'mini-kpi--warn' : ''}`}>
      <div className="mini-kpi__label">{label}</div>
      <div className="mini-kpi__value">{value}</div>
      {sub ? <div className="mini-kpi__sub">{sub}</div> : null}
    </div>
  );
}

function buildCpuOption(snapshots) {
  const xs  = snapshots.map((s) => s.timestamp);
  const col = (key) => snapshots.map((s) => {
    const v = s.rows?.[0]?.[key];
    const n = Number(v);
    return Number.isFinite(n) ? n : null;
  });
  return {
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis' },
    legend: {
      type: 'scroll', top: 2, right: 8,
      itemWidth: 10, itemHeight: 8, itemGap: 10,
      textStyle: { color: '#8a93a6', fontSize: 10 },
    },
    grid: { left: 40, right: 10, top: 24, bottom: 24 },
    xAxis: { type: 'time', data: xs, axisLine: { lineStyle: { color: '#262c3b' } }, axisLabel: { color: '#8a93a6', fontSize: 10 } },
    yAxis: { type: 'value', max: 100, axisLine: { lineStyle: { color: '#262c3b' } }, axisLabel: { color: '#8a93a6', fontSize: 10 }, splitLine: { lineStyle: { color: '#262c3b' } } },
    series: [
      { name: 'User',   type: 'line', stack: 'cpu', areaStyle: {}, showSymbol: false, smooth: true, itemStyle: { color: '#4ea1ff' }, data: xs.map((t, i) => [t, col('user_pct')[i]]) },
      { name: 'System', type: 'line', stack: 'cpu', areaStyle: {}, showSymbol: false, smooth: true, itemStyle: { color: '#2dd4a4' }, data: xs.map((t, i) => [t, col('system_pct')[i]]) },
      { name: 'IOWait', type: 'line', stack: 'cpu', areaStyle: {}, showSymbol: false, smooth: true, itemStyle: { color: '#ef5a5a' }, data: xs.map((t, i) => [t, col('iowait_pct')[i]]) },
    ],
    animation: false,
  };
}

/* ------------------------------- DISK -------------------------------- */

function DiskPanel({ diskSnapshots }) {
  const latest = diskSnapshots.at(-1);
  if (!latest || !latest.rows?.length) {
    return <div className="placeholder">No disk data yet.</div>;
  }
  return (
    <table className="metric-table metric-table--compact">
      <thead>
        <tr>
          <th>DISK</th>
          <th style={R}>R/s</th>
          <th style={R}>W/s</th>
          <th style={R}>MB R/s</th>
          <th style={R}>MB W/s</th>
          <th style={R}>QUEUE</th>
          <th style={R}>UTIL %</th>
        </tr>
      </thead>
      <tbody>
        {latest.rows.map((r) => {
          const util = Number(r.util_pct ?? 0);
          const hot = util > 80;
          return (
            <tr key={r.name} className={hot ? 'row--hot' : undefined}>
              <td title={r.name}>{r.name}</td>
              <td style={R}>{fmtNum(r.reads_per_s)}</td>
              <td style={R}>{fmtNum(r.writes_per_s)}</td>
              <td style={R}>{fmtNum(r.read_mb_per_s)}</td>
              <td style={R}>{fmtNum(r.write_mb_per_s)}</td>
              <td style={R}>{r.queue ?? 0}</td>
              <td style={R}>{fmtPct(r.util_pct)}%</td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

const R = { textAlign: 'right' };

/* ----------------------------- STORAGE ------------------------------- */

/**
 * Filesystem saturation table. Mirrors DiskPanel's compact table style so the
 * Storage tab doesn't introduce a new visual vocabulary. `hot` row turns red
 * above 90% (matches the db-filesystem-full alert threshold); `warn` above 75%
 * to give a visual runway before the alert.
 */
function StoragePanel({ fsSnapshots }) {
  const latest = fsSnapshots?.at(-1);
  if (!latest || !latest.rows?.length) {
    return <div className="placeholder">No filesystem data — set host-exporter-url for this target.</div>;
  }
  return (
    <table className="metric-table metric-table--compact">
      <thead>
        <tr>
          <th>MOUNT</th>
          <th>FSTYPE</th>
          <th style={R}>USED %</th>
          <th style={R}>USED</th>
          <th style={R}>SIZE</th>
        </tr>
      </thead>
      <tbody>
        {latest.rows.map((r) => {
          const used = Number(r.used_pct ?? 0);
          const cls = used > 90 ? 'row--hot' : used > 75 ? 'row--warn' : undefined;
          return (
            <tr key={r.mountpoint} className={cls}>
              <td title={r.mountpoint}>{r.mountpoint}</td>
              <td>{r.fstype}</td>
              <td style={R}>{fmtPct(r.used_pct)}%</td>
              <td style={R}>{fmtGb(r.used_gb)}</td>
              <td style={R}>{fmtGb(r.size_gb)}</td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

/* ------------------------------ ENGINE ------------------------------- */

const S_NAME  = ['variable_name', 'name'];
const S_VALUE = ['value'];

function getStatus(rows, name) {
  const want = name.toUpperCase();
  for (const r of rows || []) {
    const n = String(pick(r, ...S_NAME) ?? '').toUpperCase();
    if (n === want) {
      const v = Number(pick(r, ...S_VALUE) ?? 0);
      return Number.isFinite(v) ? v : 0;
    }
  }
  return 0;
}

/**
 * Engine internals — all three metrics derive from the existing `status`
 * snapshots (no new backend query). Rates use the newest two snapshots so
 * "per second" reflects current activity, not lifetime averages.
 *   Thread cache hit rate  = 1 - Threads_created / Connections
 *   Table open cache misses/s = Δ Opened_tables / Δt
 *   On-disk temp-table ratio  = Δ Created_tmp_disk_tables / Δ Created_tmp_tables
 */
function EnginePanel({ statusSnapshots, saturationSnapshots }) {
  const stats = useMemo(() => computeEngine(statusSnapshots), [statusSnapshots]);
  const sat = saturationSnapshots?.at(-1)?.rows?.[0] ?? null;
  if (!stats && !sat) return <div className="placeholder">Collecting engine baseline…</div>;

  const { threadCachePct, openTablesPerSec, tmpDiskPct, threadsCreated, connections } = stats ?? {};

  return (
    <div className="infra-engine">
      {sat ? (
        <EngineKpi
          label="Session Saturation"
          value={`${fmtPct(sat.session_utilization_pct)}%`}
          sub={`${fmtInt(sat.threads_connected)} / ${fmtInt(sat.max_connections)} max_connections`}
          warn={Number(sat.session_utilization_pct) > 85}
        />
      ) : null}
      {stats ? (
        <>
          <EngineKpi
            label="Thread Cache Hit"
            value={`${fmtPct(threadCachePct)}%`}
            sub={`${fmtInt(threadsCreated)} created / ${fmtInt(connections)} conns`}
            warn={threadCachePct < 90}
          />
          <EngineKpi
            label="Table Open Misses"
            value={`${fmtPerSec(openTablesPerSec)}/s`}
            sub="Δ opened_tables per second"
            warn={openTablesPerSec > 1}
          />
          <EngineKpi
            label="Temp Tables on Disk"
            value={`${fmtPct(tmpDiskPct)}%`}
            sub="disk-based / total temp tables"
            warn={tmpDiskPct > 20}
          />
        </>
      ) : null}
    </div>
  );
}

function EngineKpi({ label, value, sub, warn }) {
  return (
    <div className={`engine-kpi ${warn ? 'engine-kpi--warn' : ''}`}>
      <div className="engine-kpi__label">{label}</div>
      <div className="engine-kpi__value">{value}</div>
      <div className="engine-kpi__sub">{sub}</div>
    </div>
  );
}

function computeEngine(snapshots) {
  if (!snapshots || snapshots.length < 2) return null;
  const curr = snapshots[snapshots.length - 1];
  const prev = snapshots[snapshots.length - 2];

  const threadsCreated = getStatus(curr.rows, 'Threads_created');
  const connections    = getStatus(curr.rows, 'Connections');
  const threadCachePct = connections > 0
    ? Math.max(0, Math.min(100, 100 * (1 - threadsCreated / connections)))
    : 0;

  const dt = Math.max(0.001, (Date.parse(curr.timestamp) - Date.parse(prev.timestamp)) / 1000);
  const dOpened = Math.max(0,
    getStatus(curr.rows, 'Opened_tables') - getStatus(prev.rows, 'Opened_tables'));
  const openTablesPerSec = dOpened / dt;

  const dTmp     = Math.max(0, getStatus(curr.rows, 'Created_tmp_tables')      - getStatus(prev.rows, 'Created_tmp_tables'));
  const dTmpDisk = Math.max(0, getStatus(curr.rows, 'Created_tmp_disk_tables') - getStatus(prev.rows, 'Created_tmp_disk_tables'));
  const tmpDiskPct = dTmp > 0 ? 100 * dTmpDisk / dTmp : 0;

  return { threadCachePct, openTablesPerSec, tmpDiskPct, threadsCreated, connections };
}

/* ---------------------------- formatters ----------------------------- */

function fmtNum(v) {
  const n = Number(v);
  if (!Number.isFinite(n)) return '—';
  return n.toLocaleString(undefined, { maximumFractionDigits: 2 });
}
function fmtInt(v) {
  const n = Number(v);
  if (!Number.isFinite(n)) return '—';
  return Math.trunc(n).toLocaleString();
}
function fmtPct(v) {
  const n = Number(v);
  if (!Number.isFinite(n)) return '—';
  return n.toLocaleString(undefined, { maximumFractionDigits: 1 });
}
function fmtPerSec(v) {
  const n = Number(v);
  if (!Number.isFinite(n)) return '0';
  return n.toLocaleString(undefined, { maximumFractionDigits: 2 });
}
function fmtMb(mb) {
  const n = Number(mb);
  if (!Number.isFinite(n)) return '—';
  if (n >= 1024) return `${(n / 1024).toLocaleString(undefined, { maximumFractionDigits: 1 })} GB`;
  return `${Math.trunc(n).toLocaleString()} MB`;
}
function fmtGb(gb) {
  const n = Number(gb);
  if (!Number.isFinite(n)) return '—';
  if (n >= 1024) return `${(n / 1024).toLocaleString(undefined, { maximumFractionDigits: 1 })} TB`;
  return `${n.toLocaleString(undefined, { maximumFractionDigits: 1 })} GB`;
}
