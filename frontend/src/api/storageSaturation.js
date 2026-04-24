/**
 * Fetch wrapper for the slow-queue storage-saturation endpoint.
 *
 * The backend returns 204 No Content until the first SlowCollector tick lands
 * in SaturationStore (freshly added target, or an engine with no
 * SlowCollector registered). `apiJson` translates that into `null`, which
 * callers render as a "waiting for first snapshot" state — not an error.
 *
 * Shape when populated: `{ targetId, engine, timestamp, items: Item[] }`
 * where Item = `{ storageType, name, totalMb, usedMb, freeMb, usedPct,
 *                 autoExtensible, severity, note }`.
 *
 * Accepts an optional AbortSignal so the tile can cancel an in-flight
 * request when the target switches or the component unmounts — the next
 * 60s tick replaces the stale request anyway.
 */

import { apiJson as request } from './http.js';

export function fetchStorageSaturation({ targetId, signal } = {}) {
  const params = new URLSearchParams();
  params.set('targetId', targetId);
  return request(`/api/storage/saturation?${params.toString()}`, { signal });
}
