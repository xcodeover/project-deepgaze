import { pick } from './rowAccess.js';

/**
 * Pure function that turns a replay snapshot + firing alerts into a Markdown
 * incident report. Kept side-effect-free so it's easy to unit test and so the
 * caller decides when/how to deliver the file (download, copy, POST, etc.).
 *
 * Inputs:
 *   targetId, targetName   — identity for the report header
 *   asOfMs                 — wall-time of the frozen snapshot (ms epoch)
 *   rangeMinMs, rangeMaxMs — available history window (may be null)
 *   replayByKey            — `${targetId}::${group}` → MetricSnapshot
 *   firingAlerts           — currently firing AlertState[]
 *   recentEvents           — recent alert events (FIRED/RESOLVED, newest first)
 *   now                    — injection point for tests (defaults to Date.now())
 */
export function buildPostMortemMarkdown({
  targetId,
  targetName,
  asOfMs,
  rangeMinMs,
  rangeMaxMs,
  replayByKey = {},
  firingAlerts = [],
  recentEvents = [],
  now = Date.now(),
}) {
  const asOfIso = isoOrDash(asOfMs);
  const lines = [];

  lines.push(`# DeepGaze Post-Mortem — ${mdEscape(targetName || targetId || 'target')}`);
  lines.push('');
  lines.push(`- **Target**: \`${targetId || '—'}\`${targetName ? ` (${mdEscape(targetName)})` : ''}`);
  lines.push(`- **Snapshot at**: ${asOfIso} (epoch ${asOfMs ?? '—'} ms)`);
  if (rangeMinMs && rangeMaxMs) {
    lines.push(`- **History window**: ${isoOrDash(rangeMinMs)} → ${isoOrDash(rangeMaxMs)}`);
  }
  lines.push(`- **Generated at**: ${isoOrDash(now)}`);
  lines.push('');
  lines.push('---');
  lines.push('');

  lines.push('## Active Alerts');
  lines.push('');
  const scoped = firingAlerts.filter((a) => !a.targetId || a.targetId === targetId);
  if (scoped.length === 0) {
    lines.push('_No alerts firing at snapshot time._');
  } else {
    lines.push('| Severity | Rule | Target | Since | Value | Message |');
    lines.push('|---|---|---|---|---|---|');
    for (const a of scoped) {
      lines.push(
        `| ${mdCell(a.severity)} | ${mdCell(a.ruleId)} | ${mdCell(a.targetId)} `
        + `| ${mdCell(isoOrDash(Date.parse(a.since)))} `
        + `| ${mdCell(formatNumber(a.lastValue))} | ${mdCell(a.message)} |`
      );
    }
  }
  lines.push('');

  lines.push('## Preceding Alert Events');
  lines.push('');
  const windowMs = 15 * 60 * 1000;
  const scopedEvents = recentEvents
    .filter((e) => e.targetId === targetId && asOfMs && Date.parse(e.timestamp) <= asOfMs
                   && Date.parse(e.timestamp) >= (asOfMs - windowMs))
    .slice(0, 10);
  if (scopedEvents.length === 0) {
    lines.push('_No alert transitions in the 15 minutes before the snapshot._');
  } else {
    lines.push('| Time | Kind | Severity | Rule | Value | Message |');
    lines.push('|---|---|---|---|---|---|');
    for (const e of scopedEvents) {
      lines.push(
        `| ${mdCell(isoOrDash(Date.parse(e.timestamp)))} | ${mdCell(e.kind)} `
        + `| ${mdCell(e.severity)} | ${mdCell(e.ruleId)} `
        + `| ${mdCell(formatNumber(e.value))} | ${mdCell(e.message)} |`
      );
    }
  }
  lines.push('');

  const key = (group) => `${targetId}::${group}`;
  const satRow = firstRow(replayByKey[key('dbSaturation')]);
  lines.push('## System Saturation');
  lines.push('');
  if (!satRow) {
    lines.push('_No saturation snapshot available._');
  } else {
    const sessPct   = num(pick(satRow, 'session_utilization_pct', 'sessionUtilizationPct'));
    const connUsed  = num(pick(satRow, 'threads_connected', 'sessions_connected', 'active_connections'));
    const connMax   = num(pick(satRow, 'max_connections', 'connection_limit'));
    const running   = num(pick(satRow, 'threads_running', 'active_sessions', 'running'));
    const lockWait  = num(pick(satRow, 'lock_wait_ms', 'lockWaitMs'));
    lines.push(`- **Sessions**: ${fmtPct(sessPct)}${connMax != null ? ` (${fmtInt(connUsed)} / ${fmtInt(connMax)})` : ''}`);
    lines.push(`- **Running threads**: ${running != null ? fmtInt(running) : '—'}`);
    lines.push(`- **Lock waits**: ${lockWait != null ? `${fmtInt(lockWait)} ms` : '—'}`);
  }
  lines.push('');

  lines.push('## Cache & Buffer Ratios');
  lines.push('');
  const ratiosRows = (replayByKey[key('ratios')]?.rows) || [];
  if (ratiosRows.length === 0) {
    lines.push('_No ratio snapshot available._');
  } else {
    lines.push('| Metric | Value | Quality | Note |');
    lines.push('|---|---|---|---|');
    for (const r of ratiosRows) {
      const label   = pick(r, 'label', 'metric') ?? 'metric';
      const value   = pick(r, 'value');
      const unit    = pick(r, 'unit') ?? 'pct';
      const quality = pick(r, 'quality') ?? '';
      const note    = pick(r, 'note') ?? '';
      lines.push(`| ${mdCell(label)} | ${mdCell(formatRatioValue(value, unit))} | ${mdCell(quality)} | ${mdCell(note)} |`);
    }
  }
  lines.push('');

  lines.push('## Top Wait Events');
  lines.push('');
  const waitRows = (replayByKey[key('topWaits')]?.rows) || [];
  if (waitRows.length === 0) {
    lines.push('_No wait-event data captured at this timestamp._');
  } else {
    lines.push(renderTable(waitRows.slice(0, 10)));
  }
  lines.push('');

  lines.push('## Top / Slow Queries');
  lines.push('');
  const topSqlSnap =
    replayByKey[key('topSql')]
    || replayByKey[key('topDigests')];
  const slowSnap = replayByKey[key('slowQueries')];
  let addedAnyQuery = false;
  if (topSqlSnap?.rows?.length) {
    lines.push('### By wait / cost');
    lines.push('');
    lines.push(renderTable(topSqlSnap.rows.slice(0, 10)));
    lines.push('');
    addedAnyQuery = true;
  }
  if (slowSnap?.rows?.length) {
    lines.push('### Slow queries');
    lines.push('');
    lines.push(renderTable(slowSnap.rows.slice(0, 10)));
    lines.push('');
    addedAnyQuery = true;
  }
  if (!addedAnyQuery) {
    lines.push('_No top/slow query data captured at this timestamp._');
    lines.push('');
  }

  lines.push('## Lock Tree');
  lines.push('');
  const blockerRows = (replayByKey[key('blockers')]?.rows) || [];
  if (blockerRows.length === 0) {
    lines.push('_No blocking detected at this timestamp._');
  } else {
    const tree = buildLockTree(blockerRows);
    for (const root of tree.roots) {
      renderLockNode(root, tree.nodeInfo, tree.children, 0, new Set(), lines);
    }
  }
  lines.push('');

  lines.push('## Processlist (snapshot)');
  lines.push('');
  const plRows = (replayByKey[key('processlist')]?.rows) || [];
  if (plRows.length === 0) {
    lines.push('_No active sessions captured._');
  } else {
    lines.push(renderTable(plRows.slice(0, 20)));
  }
  lines.push('');

  lines.push('---');
  lines.push('');
  lines.push('_Generated by DeepGaze — Agentless DB Monitoring._');
  lines.push('');

  return lines.join('\n');
}

/**
 * Browser-side download: wraps the Markdown string in a Blob, synthesises an
 * anchor with the `download` attribute, and revokes the object URL afterwards.
 * Separated from the pure builder so unit tests never touch the DOM.
 */
export function downloadMarkdown(filename, content) {
  const blob = new Blob([content], { type: 'text/markdown;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  // Defer revoke so Safari gets a chance to start the download.
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export function postMortemFilename(targetId, asOfMs) {
  const safeTarget = String(targetId || 'target').replace(/[^a-z0-9_-]+/gi, '_');
  const stamp = Number.isFinite(asOfMs)
    ? new Date(asOfMs).toISOString().replace(/[:.]/g, '-').replace(/Z$/, 'Z')
    : 'unknown-time';
  return `deepgaze-postmortem-${safeTarget}-${stamp}.md`;
}

/* ---------- helpers ---------- */

function firstRow(snap) {
  return snap?.rows?.[0] ?? null;
}

function renderTable(rows) {
  if (!rows || rows.length === 0) return '_(empty)_';
  const cols = Object.keys(rows[0]);
  const header = `| ${cols.join(' | ')} |`;
  const sep    = `| ${cols.map(() => '---').join(' | ')} |`;
  const body = rows.map((r) =>
    `| ${cols.map((c) => mdCell(r[c])).join(' | ')} |`
  );
  return [header, sep, ...body].join('\n');
}

function buildLockTree(rows) {
  const nodeInfo = new Map();
  const children = new Map();
  const waiters  = new Set();
  for (const r of rows) {
    const w = String(pick(r, 'waiting_trx') ?? '');
    const b = String(pick(r, 'blocking_trx') ?? '');
    if (!w || !b) continue;
    if (!nodeInfo.has(b)) nodeInfo.set(b, {
      trx: b,
      user:  pick(r, 'blocking_user'),
      host:  pick(r, 'blocking_host'),
      secs:  pick(r, 'blocking_secs'),
      query: pick(r, 'blocking_query'),
    });
    if (!nodeInfo.has(w)) nodeInfo.set(w, {
      trx: w,
      user:  pick(r, 'waiting_user'),
      host:  pick(r, 'waiting_host'),
      secs:  pick(r, 'waiting_secs'),
      query: pick(r, 'waiting_query'),
    });
    if (!children.has(b)) children.set(b, []);
    children.get(b).push(w);
    waiters.add(w);
  }
  const roots = [];
  for (const [id, info] of nodeInfo) {
    if (!waiters.has(id)) roots.push(info);
  }
  return { roots, nodeInfo, children };
}

function renderLockNode(info, nodeInfo, children, depth, seen, out) {
  if (seen.has(info.trx)) {
    out.push(`${'  '.repeat(depth)}- (cycle at trx ${info.trx})`);
    return;
  }
  const next = new Set(seen);
  next.add(info.trx);
  const who = `${info.user || '?'}@${info.host || '?'}`;
  const secs = info.secs != null ? `${info.secs}s` : '?s';
  const q = (info.query || '—').toString().replace(/\s+/g, ' ').trim();
  const qClipped = q.length > 160 ? `${q.slice(0, 157)}…` : q;
  out.push(`${'  '.repeat(depth)}- **trx ${info.trx}** — ${who} (${secs}): \`${mdInline(qClipped)}\``);
  const kids = children.get(info.trx) || [];
  for (const k of kids) {
    const child = nodeInfo.get(k);
    if (child) renderLockNode(child, nodeInfo, children, depth + 1, next, out);
  }
}

function isoOrDash(ms) {
  if (!ms || !Number.isFinite(Number(ms))) return '—';
  return new Date(Number(ms)).toISOString().replace('.000', '');
}

function num(v) {
  if (v == null) return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}
function fmtPct(v)  { return v == null ? '—' : `${v.toLocaleString(undefined, { maximumFractionDigits: 1 })}%`; }
function fmtInt(v)  { return v == null ? '—' : Math.trunc(v).toLocaleString(); }

function formatNumber(v) {
  if (v == null || v === '') return '';
  const n = Number(v);
  if (!Number.isFinite(n)) return String(v);
  return Number.isInteger(n) ? n.toLocaleString() : n.toLocaleString(undefined, { maximumFractionDigits: 3 });
}

function formatRatioValue(v, unit) {
  if (v == null) return '—';
  const n = Number(v);
  if (!Number.isFinite(n)) return String(v);
  switch (unit) {
    case 'pct':   return `${n.toLocaleString(undefined, { maximumFractionDigits: 2 })}%`;
    case 'sec':   return `${n.toLocaleString(undefined, { maximumFractionDigits: 0 })} s`;
    case 'ratio': return n.toLocaleString(undefined, { maximumFractionDigits: 3 });
    case 'count': return Math.trunc(n).toLocaleString();
    default:      return n.toLocaleString(undefined, { maximumFractionDigits: 2 });
  }
}

function mdEscape(s) {
  if (s == null) return '';
  return String(s).replace(/([\\`*_{}[\]()#+!])/g, '\\$1');
}

function mdCell(v) {
  if (v == null) return '';
  const s = String(v)
    .replace(/\r?\n+/g, ' ')
    .replace(/\|/g, '\\|')
    .replace(/`/g, '\\`');
  return s.length > 200 ? `${s.slice(0, 197)}…` : s;
}

function mdInline(s) {
  return String(s).replace(/`/g, '\\`');
}
