/**
 * V9 Time Machine client. Two thin fetch wrappers against the SQLite-backed
 * history endpoints. Both return the JSON body verbatim — shape conversion
 * is the store's responsibility. JWT auth rides on the apiFetch wrapper.
 */

import { apiFetch, apiJson as request } from './http.js';

export async function fetchRange(targetId) {
  const res = await apiFetch(`/api/history/range?targetId=${encodeURIComponent(targetId)}`);
  if (!res.ok) throw new Error(`history/range HTTP ${res.status}`);
  return res.json();
}

export async function fetchReplay(targetId, timestampMs) {
  const url = `/api/history/replay?targetId=${encodeURIComponent(targetId)}&timestampMs=${Math.floor(timestampMs)}`;
  const res = await apiFetch(url);
  if (!res.ok) throw new Error(`history/replay HTTP ${res.status}`);
  return res.json();
}

export function fetchHistorySettings() {
  return request('/api/settings/history');
}

export function updateHistorySettings(dto) {
  return request('/api/settings/history', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(dto),
  });
}
