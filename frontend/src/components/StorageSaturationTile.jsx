import { useEffect, useMemo, useRef, useState } from 'react';
import { fetchStorageSaturation } from '../api/storageSaturation.js';

/**
 * Storage Saturation tile — renders a vertical list of progress bars for
 * every tablespace / UNDO / TEMP / FRA / archive-log component the slow
 * collector reported for the current target. Data is last-wins cached on
 * the server, so a GET never blocks on a dictionary query.
 *
 * Polling is local (60s) because the slow queue writes at 60s cadence and
 * we deliberately kept this tile off the 1s SSE stream — streaming a stable
 * value every second would just be noise. We still issue the first request
 * immediately so operators don't stare at a skeleton while the interval
 * warms up.
 *
 * Severity classification (OK / WARN / CRIT) is decided server-side so the
 * policy (80 / 90%) stays in one place. The client just maps the enum to a
 * colour and a sort bucket — CRIT pinned to the top so the eye finds the
 * thing about to page someone.
 */

const POLL_INTERVAL_MS = 60_000;

const SEVERITY_RANK = { CRIT: 0, WARN: 1, OK: 2 };

const TYPE_LABEL = {
  TABLESPACE:   'TBS',
  UNDO:         'UNDO',
  TEMP:         'TEMP',
  FRA:          'FRA',
  ARCHIVE_LOG:  'ARCH',
  TEMPDB:       'TEMPDB',
  TX_LOG:       'TXLOG',
  DATAFILE:     'FILE',
};

export default function StorageSaturationTile({ targetId }) {
  const [state, setState] = useState({ phase: 'loading', data: null, error: null });
  // Keep the latest payload across reloads so a transient network error
  // doesn't blank the tile — operators keep the last-known saturation in
  // view while the next tick retries.
  const lastDataRef = useRef(null);

  useEffect(() => {
    if (!targetId) return undefined;
    let cancelled = false;
    let timer = null;
    const ctrl = new AbortController();

    async function load() {
      try {
        const dto = await fetchStorageSaturation({ targetId, signal: ctrl.signal });
        if (cancelled) return;
        if (dto == null) {
          setState({
            phase: lastDataRef.current ? 'ok' : 'waiting',
            data: lastDataRef.current,
            error: null,
          });
        } else {
          lastDataRef.current = dto;
          setState({ phase: 'ok', data: dto, error: null });
        }
      } catch (err) {
        if (cancelled || err?.name === 'AbortError') return;
        setState({
          phase: lastDataRef.current ? 'ok' : 'error',
          data: lastDataRef.current,
          error: err?.message || String(err),
        });
      } finally {
        if (!cancelled) {
          timer = setTimeout(load, POLL_INTERVAL_MS);
        }
      }
    }

    lastDataRef.current = null;
    setState({ phase: 'loading', data: null, error: null });
    load();

    return () => {
      cancelled = true;
      ctrl.abort();
      if (timer) clearTimeout(timer);
    };
  }, [targetId]);

  const items = useMemo(() => sortItems(state.data?.items), [state.data]);

  if (state.phase === 'loading') return <Hint text="Collecting first storage snapshot…" />;
  if (state.phase === 'waiting') return <Hint text="Awaiting first saturation tick (60s cadence)…" />;
  if (state.phase === 'error' && !state.data) return <Hint text={`Failed to load: ${state.error}`} tone="error" />;
  if (!items || items.length === 0) return <Hint text="No storage components reported." />;

  return (
    <div className="storage-tile">
      <ul className="storage-list">
        {items.map((it, idx) => (
          <StorageRow key={`${it.storageType}:${it.name}:${idx}`} item={it} />
        ))}
      </ul>
      <footer className="storage-tile__footer">
        <span>{items.length} component{items.length === 1 ? '' : 's'}</span>
        <span>updated {fmtAgo(state.data?.timestamp)}</span>
      </footer>
    </div>
  );
}

function StorageRow({ item }) {
  const { storageType, name, usedPct, usedMb, totalMb, autoExtensible, severity, note } = item;
  const pct = clampPct(usedPct);
  const sevClass = severity ? `storage-bar--${severity.toLowerCase()}` : '';
  const tag = TYPE_LABEL[storageType] || storageType || '—';
  const pctLabel = usedPct == null ? '—' : `${formatPct(usedPct)}%`;
  const sizeLabel = (usedMb != null && totalMb != null)
    ? `${formatMb(usedMb)} / ${formatMb(totalMb)}`
    : null;
  const titleBits = [name];
  if (sizeLabel) titleBits.push(sizeLabel);
  if (autoExtensible) titleBits.push('autoextensible');
  if (note) titleBits.push(note);

  return (
    <li className="storage-row" title={titleBits.join(' · ')}>
      <div className="storage-row__head">
        <span className={`storage-row__tag storage-row__tag--${storageType?.toLowerCase() || 'default'}`}>{tag}</span>
        <span className="storage-row__name">{name}</span>
        {autoExtensible ? <span className="storage-row__ae" title="autoextensible">AE</span> : null}
        <span className="storage-row__pct">{pctLabel}</span>
      </div>
      <div className="storage-bar">
        <div className={`storage-bar__fill ${sevClass}`} style={{ width: `${pct}%` }} />
      </div>
      {(sizeLabel || note) && (
        <div className="storage-row__sub">
          {sizeLabel ? <span>{sizeLabel}</span> : null}
          {note ? <span className="storage-row__note">{note}</span> : null}
        </div>
      )}
    </li>
  );
}

function Hint({ text, tone }) {
  return (
    <div className={`storage-tile storage-tile--hint${tone === 'error' ? ' storage-tile--error' : ''}`}>
      <span>{text}</span>
    </div>
  );
}

function sortItems(items) {
  if (!Array.isArray(items)) return [];
  // Stable-ish: severity first (CRIT on top), then usedPct desc, then name.
  return [...items].sort((a, b) => {
    const sa = SEVERITY_RANK[a.severity] ?? 9;
    const sb = SEVERITY_RANK[b.severity] ?? 9;
    if (sa !== sb) return sa - sb;
    const pa = a.usedPct ?? -1;
    const pb = b.usedPct ?? -1;
    if (pa !== pb) return pb - pa;
    return (a.name || '').localeCompare(b.name || '');
  });
}

function clampPct(v) {
  if (v == null || !Number.isFinite(Number(v))) return 0;
  const n = Number(v);
  if (n < 0) return 0;
  if (n > 100) return 100;
  return n;
}

function formatPct(v) {
  const n = Number(v);
  if (!Number.isFinite(n)) return '—';
  if (n >= 10) return n.toFixed(0);
  return n.toFixed(1);
}

function formatMb(mb) {
  const n = Number(mb);
  if (!Number.isFinite(n)) return '—';
  if (n >= 1024 * 1024) return `${(n / (1024 * 1024)).toFixed(1)} TB`;
  if (n >= 1024)        return `${(n / 1024).toFixed(1)} GB`;
  return `${Math.round(n)} MB`;
}

function fmtAgo(iso) {
  if (!iso) return '—';
  const t = Date.parse(iso);
  if (!Number.isFinite(t)) return '—';
  const secs = Math.max(0, Math.round((Date.now() - t) / 1000));
  if (secs < 60)  return `${secs}s ago`;
  if (secs < 3600) return `${Math.floor(secs / 60)}m ago`;
  return `${Math.floor(secs / 3600)}h ago`;
}
