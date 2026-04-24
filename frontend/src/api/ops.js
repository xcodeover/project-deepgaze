/**
 * Fetch wrappers for the mutation boundary (/api/ops/**). All calls ride the
 * session JWT (via apiFetch) — an authenticated operator is authorised to
 * kill sessions on their fleet; the shared-secret X-Deepgaze-Auth header
 * from earlier builds is obsolete now that every /api/** request is
 * individually identified.
 *
 * Returns `{ ok, status, body }` instead of throwing so the kill modal can
 * render typed error messages (REJECTED vs UNAVAILABLE vs auth failure)
 * rather than a generic "request failed". On 401 the http wrapper will
 * already have redirected to /login — we still return a shaped result so
 * any awaiting caller can unwind cleanly.
 */

import { apiFetch } from './http.js';

export async function killSession(targetId, threadId, { signal } = {}) {
  const url = `/api/ops/targets/${encodeURIComponent(targetId)}/sessions/${encodeURIComponent(threadId)}/kill`;
  const res = await apiFetch(url, { method: 'POST', signal });
  let body = null;
  try { body = await res.json(); } catch { /* 401 has no body */ }
  return { ok: res.ok, status: res.status, body };
}
