import { create } from 'zustand';
import { AlertStreamConnection } from '../api/alertStream.js';
import { apiFetch, withAuthQuery } from '../api/http.js';

const EVENT_LOG_MAX = 50;
const keyOf = (ruleId, targetId) => `${ruleId}::${targetId}`;

/**
 * Alerts mirror the metrics store's shape: a passive lifecycle owner that
 * hydrates from REST on startup and subscribes to SSE for live transitions.
 *
 *   firing     — (ruleId,targetId) → AlertState for every currently-firing alert
 *   recent     — most-recent events, newest first (for a future history pane)
 *
 * We deliberately keep only `firing` here, not `all` states. An OK state is
 * the absence of a firing entry — representing it explicitly would just mean
 * re-deriving it every render. The backend's /api/alerts still returns all
 * states for debugging via curl.
 */
export const useAlertsStore = create((set, get) => {
  let connection = null;

  return {
    status: 'idle',
    firing: {},     // key → AlertState
    recent: [],     // AlertEvent[], newest first

    bootstrap: async (url = '/api/alerts/firing') => {
      try {
        const res = await apiFetch(url);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const states = await res.json();
        const firing = {};
        for (const s of states) firing[keyOf(s.ruleId, s.targetId)] = s;
        set({ firing });
      } catch (err) {
        console.warn('[alertsStore] bootstrap failed', err);
      }
    },

    connect: (url = '/api/stream/alerts') => {
      if (connection) return;
      connection = new AlertStreamConnection({
        // SSE can't set Authorization headers, so the JWT rides as ?token=.
        url: withAuthQuery(url),
        onStatus: (status) => set({ status }),
        onEvent:  (ev)     => get().ingest(ev),
      });
      connection.connect();
    },

    disconnect: () => {
      if (!connection) return;
      connection.disconnect();
      connection = null;
    },

    ingest: (event) => set((state) => {
      if (!event || !event.ruleId || !event.targetId) return state;
      const k = keyOf(event.ruleId, event.targetId);

      const firing = { ...state.firing };
      if (event.kind === 'FIRED') {
        firing[k] = {
          ruleId:     event.ruleId,
          targetId:   event.targetId,
          severity:   event.severity,
          status:     'FIRING',
          since:      event.timestamp,
          lastEvalAt: event.timestamp,
          lastValue:  event.value,
          message:    event.message,
        };
      } else if (event.kind === 'RESOLVED') {
        delete firing[k];
      }

      const recent = [event, ...state.recent].slice(0, EVENT_LOG_MAX);
      return { firing, recent };
    }),
  };
});

export const selectFiring       = (s) => Object.values(s.firing);
export const selectAlertsStatus = (s) => s.status;
export const selectRecentEvents = (s) => s.recent;
