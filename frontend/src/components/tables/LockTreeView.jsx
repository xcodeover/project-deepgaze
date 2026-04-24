import { pick } from '../../lib/rowAccess.js';

/**
 * Nested adjacency rendering of the current blocker/waiter pairs. Each row
 * in the snapshot is one blocking edge; a transaction that is a blocker but
 * never a waiter is a root. Descends recursively with a `seen` guard so a
 * pathological cycle (shouldn't happen at the InnoDB trx level, but defensive)
 * renders as "(cycle)" rather than recursing forever.
 *
 * Each node also carries the `pid` (mysql_thread_id) captured from the
 * originating row so Active Response can KILL the underlying connection —
 * the trx_id alone cannot be used for KILL, only the processlist id can.
 */
export default function LockTreeView({ rows, onKill }) {
  const { roots, nodeInfo, childrenByTrx } = buildTree(rows);

  return (
    <ul className="lock-tree">
      {roots.map((info) => (
        <TreeNode
          key={info.trx}
          info={info}
          nodeInfo={nodeInfo}
          childrenByTrx={childrenByTrx}
          seen={new Set()}
          onKill={onKill}
        />
      ))}
    </ul>
  );
}

function TreeNode({ info, nodeInfo, childrenByTrx, seen, onKill }) {
  if (seen.has(info.trx)) {
    return <li className="lock-node lock-node--cycle">(cycle at trx {info.trx})</li>;
  }
  const next = new Set(seen);
  next.add(info.trx);
  const kids = childrenByTrx.get(info.trx) || [];

  return (
    <li>
      <div className="lock-node">
        <span className="lock-node__trx">trx {info.trx}</span>
        <span className="lock-node__who">{info.user || '?'}@{info.host || '?'}</span>
        <span className="lock-node__secs">{info.secs ?? 0}s</span>
        <code className="lock-node__sql" title={info.query || ''}>{info.query || '—'}</code>
        {typeof onKill === 'function' && info.pid != null && (
          <button
            type="button"
            className="kill-btn kill-btn--tree"
            title={`Kill PID ${info.pid}`}
            onClick={() => onKill({
              id:        info.pid,
              user:      info.user,
              host:      info.host,
              command:   'Query',
              time_secs: info.secs,
              info:      info.query,
            })}
          >Kill</button>
        )}
      </div>
      {kids.length > 0 && (
        <ul>
          {kids.map((k) => (
            <TreeNode
              key={k}
              info={nodeInfo.get(k)}
              nodeInfo={nodeInfo}
              childrenByTrx={childrenByTrx}
              seen={next}
              onKill={onKill}
            />
          ))}
        </ul>
      )}
    </li>
  );
}

function buildTree(rows) {
  const nodeInfo = new Map();
  const childrenByTrx = new Map();
  const waiters  = new Set();

  for (const r of rows) {
    const w = String(pick(r, 'waiting_trx') ?? '');
    const b = String(pick(r, 'blocking_trx') ?? '');
    if (!w || !b) continue;

    if (!nodeInfo.has(b)) nodeInfo.set(b, {
      trx:   b,
      pid:   pick(r, 'blocking_process_id', 'blocking_pid'),
      user:  pick(r, 'blocking_user'),
      host:  pick(r, 'blocking_host'),
      secs:  pick(r, 'blocking_secs'),
      query: pick(r, 'blocking_query'),
    });
    if (!nodeInfo.has(w)) nodeInfo.set(w, {
      trx:   w,
      pid:   pick(r, 'waiting_process_id', 'waiting_pid'),
      user:  pick(r, 'waiting_user'),
      host:  pick(r, 'waiting_host'),
      secs:  pick(r, 'waiting_secs'),
      query: pick(r, 'waiting_query'),
    });

    if (!childrenByTrx.has(b)) childrenByTrx.set(b, []);
    childrenByTrx.get(b).push(w);
    waiters.add(w);
  }

  const roots = [];
  for (const [id, info] of nodeInfo) {
    if (!waiters.has(id)) roots.push(info);
  }
  return { roots, nodeInfo, childrenByTrx };
}
