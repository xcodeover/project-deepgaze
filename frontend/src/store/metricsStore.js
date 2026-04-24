import { create } from 'zustand';
import { MetricStreamConnection } from '../api/metricStream.js';
import { fetchReplay } from '../api/history.js';
import { apiFetch, withAuthQuery } from '../api/http.js';

/**
 * Per-key history bound. At 1Hz, 3600 ≈ 1 hour of rolling window per
 * (target, group). The backend ring buffer is the source of truth — this
 * cap only keeps the in-memory store from growing unbounded on long sessions.
 */
const HISTORY_PER_KEY = 3600;

const keyOf = (targetId, group) => `${targetId}::${group}`;

/**
 * The store owns the SSE lifecycle. UI components only see selectors —
 * they don't import the connection class, don't call EventSource, and
 * don't know SSE exists.
 */
export const useMetricsStore = create((set, get) => {
  let connection = null;

  return {
    status: 'idle',
    targets: [],         // sorted, stable reference; new array only when set changes
    namesById: {},       // targetId → display name (last seen from snapshot.targetName)
    byKey: {},           // key → snapshot[] (chronological)

    // ---- V9 Time Machine (replay) state ---------------------------------
    // replayActive gates the ingest function: live SSE frames no longer
    // extend byKey (preserves chart-pause semantics), but the freshest frame
    // per group_key is still captured into liveShadowByKey so Performance
    // Diff tiles (RatiosTile / SaturationTile) can compare historical vs.
    // current values.
    replayActive: false,
    replayTargetId: null,
    replayAsOfMs: null,
    replayRangeMinMs: null,
    replayRangeMaxMs: null,
    replayByKey: {},
    replayLoading: false,
    replayError: null,
    replaySeq: 0,         // ignores stale fetches during fast scrubbing
    liveShadowByKey: {},  // key → latest MetricSnapshot captured while paused

    connect: (url) => {
      if (connection) return;
      connection = new MetricStreamConnection({
        // SSE can't set Authorization headers, so the JWT rides as ?token=.
        url: withAuthQuery(url),
        onStatus: (status) => set({ status }),
        onMessage: (snap) => get().ingest(snap),
      });
      connection.connect();
    },

    disconnect: () => {
      if (!connection) return;
      connection.disconnect();
      connection = null;
    },

    ingest: (snap) => set((state) => {
      if (!snap || !snap.targetId || !snap.metricGroup) return state;

      const k = keyOf(snap.targetId, snap.metricGroup);

      // While replaying: don't extend the paused time-series. Still record
      // the latest frame per key into liveShadowByKey so the diff tiles can
      // render "Live: X (▲ Y%)" next to the historical value.
      if (state.replayActive) {
        return {
          liveShadowByKey: { ...state.liveShadowByKey, [k]: snap },
        };
      }

      const prev = state.byKey[k] || [];
      const next = prev.length >= HISTORY_PER_KEY
        ? [...prev.slice(prev.length - HISTORY_PER_KEY + 1), snap]
        : [...prev, snap];

      const targets = state.targets.includes(snap.targetId)
        ? state.targets
        : [...state.targets, snap.targetId].sort();

      const name = snap.targetName;
      const namesById = (name && state.namesById[snap.targetId] !== name)
        ? { ...state.namesById, [snap.targetId]: name }
        : state.namesById;

      return {
        byKey: { ...state.byKey, [k]: next },
        targets,
        namesById,
      };
    }),

    /**
     * Bulk insert many snapshots in a single state update — used by bootstrap()
     * to hydrate from /api/buffer without triggering thousands of re-renders.
     * Assumes input is roughly chronological (the backend ring buffer is).
     * Sorts per-key by timestamp to be safe, then applies the history cap once.
     */
    ingestBulk: (snapshots) => set((state) => {
      if (!snapshots || snapshots.length === 0) return state;

      const grouped = {};
      const targetSet = new Set(state.targets);
      const namesById = { ...state.namesById };
      let namesChanged = false;
      for (const snap of snapshots) {
        if (!snap || !snap.targetId || !snap.metricGroup) continue;
        const k = keyOf(snap.targetId, snap.metricGroup);
        (grouped[k] ||= []).push(snap);
        targetSet.add(snap.targetId);
        if (snap.targetName && namesById[snap.targetId] !== snap.targetName) {
          namesById[snap.targetId] = snap.targetName;
          namesChanged = true;
        }
      }

      const byKey = { ...state.byKey };
      for (const k of Object.keys(grouped)) {
        const prev = byKey[k] || [];
        const merged = prev.concat(grouped[k]);
        merged.sort((a, b) => Date.parse(a.timestamp) - Date.parse(b.timestamp));
        byKey[k] = merged.length > HISTORY_PER_KEY
          ? merged.slice(merged.length - HISTORY_PER_KEY)
          : merged;
      }

      const targetsChanged = targetSet.size !== state.targets.length;
      return {
        byKey,
        targets: targetsChanged ? Array.from(targetSet).sort() : state.targets,
        namesById: namesChanged ? namesById : state.namesById,
      };
    }),

    /**
     * Hydrate the store from the backend ring buffer. Call this before
     * connect() so incoming SSE frames extend the backfilled history rather
     * than replacing it. Returns a promise that resolves either way — a
     * backfill failure must not prevent the live stream from starting.
     */
    bootstrap: async (url = '/api/buffer') => {
      try {
        const res = await apiFetch(url);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const snapshots = await res.json();
        get().ingestBulk(snapshots);
      } catch (err) {
        console.warn('[metricsStore] bootstrap failed', err);
      }
    },

    /* ---------- V9 Time Machine actions ---------- */

    /**
     * Enter replay mode at the given wall-time. Pauses live ingestion,
     * fetches the point-in-time snapshot for each group_key, and populates
     * replayByKey. Safe to call repeatedly while scrubbing — `replaySeq`
     * guards against out-of-order responses from slow network frames.
     */
    enterReplay: async (targetId, asOfMs) => {
      const seq = get().replaySeq + 1;
      set({
        replayActive: true,
        replayTargetId: targetId,
        replayAsOfMs: asOfMs,
        replayLoading: true,
        replayError: null,
        replaySeq: seq,
      });
      try {
        const data = await fetchReplay(targetId, asOfMs);
        if (get().replaySeq !== seq) return; // a newer scrub superseded us
        const byKey = {};
        for (const snap of data.snapshots || []) {
          if (snap && snap.targetId && snap.metricGroup) {
            byKey[keyOf(snap.targetId, snap.metricGroup)] = snap;
          }
        }
        set({
          replayByKey: byKey,
          replayRangeMinMs: data.rangeMinMs ?? null,
          replayRangeMaxMs: data.rangeMaxMs ?? null,
          replayLoading: false,
        });
      } catch (err) {
        if (get().replaySeq !== seq) return;
        console.warn('[metricsStore] replay fetch failed', err);
        set({ replayLoading: false, replayError: String(err) });
      }
    },

    /**
     * Exit replay and rejoin the live stream. We refetch the ring buffer so
     * the rolling window shows the most recent minutes again — while we
     * were paused, SSE frames were dropped. The EventSource itself never
     * closed, so new frames resume feeding byKey immediately after unpause.
     */
    exitReplay: async () => {
      set({
        replayActive: false,
        replayTargetId: null,
        replayAsOfMs: null,
        replayByKey: {},
        replayLoading: false,
        replayError: null,
        replaySeq: get().replaySeq + 1,
        liveShadowByKey: {},
      });
      try {
        const res = await apiFetch('/api/buffer');
        if (res.ok) {
          const snapshots = await res.json();
          // If the user re-entered replay during the in-flight fetch, dropping
          // this bulk keeps the paused view stable — the next exitReplay will
          // re-fetch a fresher buffer anyway.
          if (!get().replayActive) get().ingestBulk(snapshots);
        }
      } catch (err) {
        console.warn('[metricsStore] exitReplay rehydrate failed', err);
      }
    },
  };
});

/* ---------- selectors ---------- */
export const selectStatus      = (s) => s.status;
export const selectTargetIds   = (s) => s.targets;
export const selectTargetNames = (s) => s.namesById;

/**
 * Tile-facing selector. In live mode returns the chronological snapshot array
 * from the ring buffer. In replay mode returns a single-element array wrapping
 * the point-in-time snapshot so chart/table components don't branch on mode.
 */
export const selectGroup = (targetId, group) => (s) => {
  const k = keyOf(targetId, group);
  if (s.replayActive) {
    const snap = s.replayByKey[k];
    return snap ? [snap] : EMPTY;
  }
  return s.byKey[k] || EMPTY;
};

/**
 * Returns the latest live snapshot for (target, group) regardless of replay
 * mode. Used by Performance Diff tiles to render "Live: X (▲ Y%)" side-by-side
 * with the historical primary value. During replay, prefers the live shadow
 * (freshest SSE frame since pause); falls back to the last frame before pause
 * for groups that haven't re-emitted yet.
 */
export const selectLatestLive = (targetId, group) => (s) => {
  const k = keyOf(targetId, group);
  if (s.replayActive) {
    const shadow = s.liveShadowByKey[k];
    if (shadow) return shadow;
  }
  const arr = s.byKey[k];
  return arr && arr.length ? arr[arr.length - 1] : null;
};

/**
 * Raw point-in-time snapshot map keyed by `${targetId}::${group}`. Consumed by
 * the post-mortem exporter so it can project every persisted group onto the
 * Markdown report in one pass rather than threading per-group props.
 */
export const selectReplayByKey = (s) => s.replayByKey;

/**
 * Wall-clock ms of the freshest snapshot seen for the given target, across
 * any group. Returns null when no snapshot has arrived. Powers the fleet
 * dashboard's "online / stale / offline" dot and the health score's staleness
 * penalty. Walking the byKey map per render is cheap: O(groups-per-target),
 * and typical fleets have <20 groups × <50 targets.
 */
export const selectLastSnapshotMs = (targetId) => (s) => {
  const prefix = `${targetId}::`;
  let latest = null;
  for (const k of Object.keys(s.byKey)) {
    if (!k.startsWith(prefix)) continue;
    const arr = s.byKey[k];
    const last = arr && arr.length ? arr[arr.length - 1] : null;
    if (!last) continue;
    const ts = Date.parse(last.timestamp);
    if (Number.isFinite(ts) && (latest == null || ts > latest)) latest = ts;
  }
  return latest;
};

export const selectReplayState = (s) => ({
  active:      s.replayActive,
  targetId:    s.replayTargetId,
  asOfMs:      s.replayAsOfMs,
  rangeMinMs:  s.replayRangeMinMs,
  rangeMaxMs:  s.replayRangeMaxMs,
  loading:     s.replayLoading,
  error:       s.replayError,
});

const EMPTY = Object.freeze([]);
