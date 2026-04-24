/**
 * Plain-English explanations of engine-specific jargon, keyed by substring so
 * a single entry covers MariaDB's "Buffer Pool Hit %", MSSQL's "Buffer Cache
 * Hit Ratio", and Oracle's "Buffer Cache Hit %" without requiring exact
 * string matches.
 *
 * Keep definitions short — the backing UI affordance is a native `title`
 * tooltip, which users hover briefly; long paragraphs get truncated.
 */
const GLOSSARY = [
  ['buffer pool hit',        'Share of DB reads served from memory instead of disk. Healthy: ≥ 95%. Falling values usually mean the buffer pool is undersized.'],
  ['buffer cache hit',       'Share of Oracle block reads that avoided disk I/O. Healthy: ≥ 90%. Sustained drops point to memory pressure.'],
  ['page life expectancy',   'Seconds data pages stay cached in SQL Server before being flushed. ≥ 300s is healthy; ≤ 100s signals memory pressure.'],
  ['query cache hit',        'Hit rate for MariaDB’s query result cache. Higher is better — misses fall back to parsing/optimising the query again.'],
  ['library cache hit',      'How often Oracle reuses a cached parse of a query. ≥ 95% is healthy; lower means parsing overhead is high.'],
  ['soft parse',             'Share of parses that reused an existing cached plan. Higher is better — hard parses are expensive.'],

  ['session utilization',    'Active DB sessions as a % of the configured connection limit. > 75% is elevated, > 90% is critical.'],
  ['sessions',               'Active DB sessions as a % of the configured connection limit. > 75% is elevated, > 90% is critical.'],
  ['running',                'Threads currently executing SQL. Spikes correlate with load; sustained highs may indicate saturation.'],
  ['lock wait',              'Total ms sessions spent waiting on row / table locks in the latest sample. Sustained > 1 s signals contention.'],
  ['lock waits',             'Total ms sessions spent waiting on row / table locks in the latest sample. Sustained > 1 s signals contention.'],
  ['blocked',                'Sessions that are currently waiting for another session to release a lock.'],
  ['deadlock',               'Two or more transactions each holding a lock the other needs. The DB kills one to break the cycle.'],

  ['top waits',              'Which wait events (I/O, lock, CPU, etc.) the engine spent the most time on recently. A change in the leader often points to the root cause.'],
  ['active sessions',        'Sessions actively executing or waiting. Grouped by wait class to show where time is going.'],
  ['ash',                    'Active Session History — a rolling trend of how many sessions were working (and on what) per second.'],

  ['processlist',            'Live list of every connection to the DB: user, host, current SQL, and state.'],
  ['lock tree',              'Visualisation of blocker → waiter chains. The root of a tree is the session holding up everyone beneath it.'],
  ['top sql',                'Queries that accumulated the most time / waits in the current window — usually the first place to look for a hot statement.'],
  ['slow queries',           'Individual executions whose runtime crossed the engine’s slow-query threshold.'],

  ['digest',                 'A normalised form of a query (literals replaced with placeholders) so repeated executions roll up into one row.'],
  ['ratio',                  'A derived percentage or ratio — always engine-interpreted so the meaning is comparable across MariaDB, Oracle, and MSSQL.'],
];

export function glossaryFor(labelOrMetric) {
  if (!labelOrMetric) return null;
  const key = String(labelOrMetric).toLowerCase().trim();
  if (!key) return null;
  for (const [needle, text] of GLOSSARY) {
    if (key.includes(needle)) return text;
  }
  return null;
}
