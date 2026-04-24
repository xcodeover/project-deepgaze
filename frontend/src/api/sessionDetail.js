/**
 * Thin fetch wrappers for the on-demand Session Detail endpoints. Kept
 * separate from the SSE modules because these are user-triggered RPC, not
 * streaming data — their lifecycle (mount/unmount/cancel) belongs to the
 * component that opened the drawer. JWT auth rides on the apiFetch wrapper.
 */

import { apiFetch } from './http.js';

export async function fetchSessionDetail(targetId, pid, { signal } = {}) {
  const res = await apiFetch(
    `/api/sessions/${encodeURIComponent(targetId)}/${encodeURIComponent(pid)}`,
    { signal }
  );
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

export async function fetchExplain(targetId, sql, { signal } = {}) {
  const res = await apiFetch(
    `/api/sessions/${encodeURIComponent(targetId)}/explain`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sql }),
      signal,
    }
  );
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}
