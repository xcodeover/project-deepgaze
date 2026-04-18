import { create } from 'zustand';
import { MetricStreamConnection } from '../api/metricStream.js';

/**
 * Per-key history bound. At 1Hz, 300 ≈ 5 minutes of rolling window per
 * (target, group). Memory stays small even with many targets.
 */
const HISTORY_PER_KEY = 300;

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
    byKey: {},           // key → snapshot[] (chronological)

    connect: (url) => {
      if (connection) return;
      connection = new MetricStreamConnection({
        url,
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
      const prev = state.byKey[k] || [];
      const next = prev.length >= HISTORY_PER_KEY
        ? [...prev.slice(prev.length - HISTORY_PER_KEY + 1), snap]
        : [...prev, snap];

      const targets = state.targets.includes(snap.targetId)
        ? state.targets
        : [...state.targets, snap.targetId].sort();

      return {
        byKey: { ...state.byKey, [k]: next },
        targets,
      };
    }),
  };
});

/* ---------- selectors ---------- */
export const selectStatus    = (s) => s.status;
export const selectTargetIds = (s) => s.targets;
export const selectGroup     = (targetId, group) => (s) => s.byKey[keyOf(targetId, group)] || EMPTY;

const EMPTY = Object.freeze([]);
