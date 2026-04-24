import { create } from 'zustand';
import * as api from '../api/targets.js';

/**
 * Source of truth for the configured target fleet. Reads /api/targets on
 * every page load, exposes CRUD actions that POST to the backend and then
 * locally mutate the store (optimistic for reorder, authoritative for
 * create / update / delete — the backend's reply is the new truth).
 *
 * Shape choice: `byId` + `order` (a list of ids sorted by displayOrder) so
 * the sidebar can render the same ordering the settings page shows without
 * resorting on every render. metricsStore still owns the SSE-derived
 * namesById for incoming snapshots, but the sidebar prefers targetsStore
 * for the primary order so deletions disappear instantly instead of
 * waiting for the stream to time out.
 */
export const useTargetsStore = create((set, get) => ({
  status: 'idle',          // idle | loading | ready | error
  error: null,
  byId: {},                // id → target DTO (as returned by /api/targets)
  order: [],               // ids in render order, lowest displayOrder first

  load: async () => {
    set({ status: 'loading', error: null });
    try {
      const list = await api.fetchTargets();
      const byId = {};
      for (const t of list) byId[t.id] = t;
      const order = sortIds(list);
      set({ byId, order, status: 'ready' });
      return list;
    } catch (err) {
      console.warn('[targetsStore] load failed', err);
      set({ status: 'error', error: String(err) });
      return [];
    }
  },

  create: async (dto) => {
    const created = await api.createTarget(dto);
    set((s) => ({
      byId: { ...s.byId, [created.id]: created },
      order: sortIds(Object.values({ ...s.byId, [created.id]: created })),
    }));
    return created;
  },

  update: async (id, patch) => {
    const updated = await api.updateTarget(id, patch);
    set((s) => ({
      byId: { ...s.byId, [id]: updated },
      order: sortIds(Object.values({ ...s.byId, [id]: updated })),
    }));
    return updated;
  },

  remove: async (id) => {
    await api.deleteTarget(id);
    set((s) => {
      const byId = { ...s.byId };
      delete byId[id];
      return { byId, order: s.order.filter((x) => x !== id) };
    });
  },

  /**
   * Flip the server-side enabled flag. The backend tears down / rebuilds
   * pools as needed; we just reflect the new value locally so the sidebar
   * (which filters to enabled-only) hides / shows the row immediately.
   */
  setEnabled: async (id, enabled) => {
    await api.setTargetEnabled(id, enabled);
    set((s) => ({
      byId: s.byId[id]
        ? { ...s.byId, [id]: { ...s.byId[id], enabled } }
        : s.byId,
    }));
  },

  /**
   * Optimistic reorder. Updates the local order array immediately for
   * instant UI feedback, then fires POST /reorder. On failure rolls back
   * by reloading from the server — simpler than diffing and robust to
   * concurrent edits.
   */
  reorder: async (newOrder) => {
    const prevOrder = get().order;
    set((s) => ({
      order: newOrder,
      byId: reassignDisplayOrder(s.byId, newOrder),
    }));
    try {
      const entries = newOrder.map((id, idx) => ({ id, displayOrder: idx }));
      await api.reorderTargets(entries);
    } catch (err) {
      console.warn('[targetsStore] reorder failed; reverting', err);
      set({ order: prevOrder });
      await get().load();
      throw err;
    }
  },

  testConnection: (dto) => api.testConnection(dto),
}));

function sortIds(list) {
  return [...list]
    .sort((a, b) => {
      const da = a.displayOrder ?? 0;
      const db = b.displayOrder ?? 0;
      if (da !== db) return da - db;
      return a.id.localeCompare(b.id);
    })
    .map((t) => t.id);
}

function reassignDisplayOrder(byId, order) {
  const next = { ...byId };
  order.forEach((id, idx) => {
    if (next[id]) next[id] = { ...next[id], displayOrder: idx };
  });
  return next;
}

/* ---------- selectors ----------
 *
 * The list+order selectors filter to enabled targets by default — disabled
 * rows are administrative-only and should not appear in the fleet sidebar
 * or dashboard. The settings admin view uses the *All* variants to see
 * every row, including disabled ones, so operators can toggle them back on.
 */
export const selectTargetsStatus = (s) => s.status;
export const selectTargetsError  = (s) => s.error;
export const selectTargetsById   = (s) => s.byId;
export const selectTargetOrder   = (s) => s.order.filter((id) => s.byId[id] && s.byId[id].enabled !== false);
export const selectTargetList    = (s) => s.order
  .map((id) => s.byId[id])
  .filter((t) => t && t.enabled !== false);
export const selectAllTargetOrder = (s) => s.order;
export const selectAllTargetList  = (s) => s.order.map((id) => s.byId[id]).filter(Boolean);
