import { useState } from 'react';

/**
 * Renders a MariaDB EXPLAIN FORMAT=JSON plan as a collapsible tree.
 *
 * Plan shape is recursive: a `query_block` has `nested_loop` / `table` /
 * `grouping_operation` / `ordering_operation` children. Rather than hard-code
 * the schema (it varies across engine versions), we walk the object and
 * render scalar rows as "key: value" and nested objects/arrays as their own
 * collapsible subtrees. Scalars never toggle — they have nothing to hide.
 *
 * Collapse state is local to each <Row>, so opening / closing one node does
 * not re-render siblings and does not fight with parent state.
 *
 * We highlight a few well-known keys (access_type, rows, filtered, key,
 * possible_keys) because those are what operators scan for first.
 */
const HIGHLIGHT_KEYS = new Set([
  'access_type', 'rows', 'rows_examined_per_scan', 'filtered',
  'key', 'possible_keys', 'ref', 'using_index', 'using_filesort',
  'using_temporary_table', 'cost_info',
]);

export default function ExplainPlanView({ plan }) {
  if (!plan) return null;
  return (
    <div className="explain-plan">
      <Node value={plan} />
    </div>
  );
}

function Node({ value }) {
  if (value == null || typeof value !== 'object') {
    return <Scalar value={value} />;
  }

  const entries = Array.isArray(value)
    ? value.map((v, i) => [`[${i}]`, v])
    : Object.entries(value);

  return (
    <ul className="explain-tree">
      {entries.map(([k, v]) => (
        <Row key={k} keyLabel={k} value={v} highlight={HIGHLIGHT_KEYS.has(k)} />
      ))}
    </ul>
  );
}

function Row({ keyLabel, value, highlight }) {
  const isNested = value != null && typeof value === 'object';
  const [open, setOpen] = useState(true);

  if (!isNested) {
    return (
      <li className="explain-row">
        <span className="explain-caret explain-caret--leaf" aria-hidden>·</span>
        <span className={`explain-key ${highlight ? 'explain-key--hot' : ''}`}>{keyLabel}</span>
        <Scalar value={value} highlight={highlight} />
      </li>
    );
  }

  return (
    <li className="explain-row">
      <button
        type="button"
        className="explain-toggle"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
      >
        <span className="explain-caret">{open ? '▾' : '▸'}</span>
        <span className={`explain-key ${highlight ? 'explain-key--hot' : ''}`}>{keyLabel}</span>
        {!open && <span className="explain-preview">{summarise(value)}</span>}
      </button>
      {open && <Node value={value} />}
    </li>
  );
}

function Scalar({ value, highlight }) {
  const text = value == null ? 'null' : String(value);
  return (
    <span className={`explain-val ${highlight ? 'explain-val--hot' : ''}`}>
      {text}
    </span>
  );
}

// One-line hint for collapsed nested nodes, so operators can see what's inside
// without expanding every branch. For arrays: "[N items]". For objects with a
// distinctive first key (table_name, access_type), surface that.
function summarise(v) {
  if (Array.isArray(v)) return `[${v.length} item${v.length === 1 ? '' : 's'}]`;
  if (v && typeof v === 'object') {
    if (v.table_name) return `{table ${v.table_name}}`;
    if (v.access_type) return `{${v.access_type}}`;
    const keys = Object.keys(v);
    return `{${keys.length} field${keys.length === 1 ? '' : 's'}}`;
  }
  return '';
}
