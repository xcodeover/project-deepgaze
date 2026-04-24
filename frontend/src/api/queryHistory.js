/**
 * Fetch wrappers for the /api/query-history surface.
 *
 * {@link searchQueryHistory} returns the raw backend DTO — the UI is
 * responsible for engine-specific latency-unit formatting since the server
 * forwards numbers in whatever unit the collector emitted.
 *
 * {@link fetchDigestTrend} batches sparkline data for the digests visible on
 * the current page so each row can render its own mini-chart without N
 * round-trips.
 *
 * Both functions accept an optional `signal` — the caller aborts in-flight
 * requests when the user changes filters or closes the modal, so a slow
 * backend can't deliver stale rows over the current view.
 */

import { apiJson as request } from './http.js';

export function searchQueryHistory({ targetId, fromMs, toMs, q, source, limit = 100, offset = 0, signal } = {}) {
  const params = new URLSearchParams();
  params.set('targetId', targetId);
  params.set('fromMs',   Math.floor(fromMs));
  params.set('toMs',     Math.floor(toMs));
  if (q && q.trim())        params.set('q', q.trim());
  if (source)               params.set('source', source);
  params.set('limit',  String(limit));
  params.set('offset', String(offset));
  return request(`/api/query-history/search?${params.toString()}`, { signal });
}

export function fetchDigestTrend({ targetId, fromMs, toMs, digests, buckets = 24, signal } = {}) {
  const unique = Array.from(new Set((digests || []).filter(Boolean))).slice(0, 200);
  if (unique.length === 0) {
    return Promise.resolve({ targetId, fromMs, toMs, bucketMs: 0, buckets, points: [] });
  }
  const params = new URLSearchParams();
  params.set('targetId', targetId);
  params.set('fromMs',   Math.floor(fromMs));
  params.set('toMs',     Math.floor(toMs));
  params.set('buckets',  String(buckets));
  for (const d of unique) params.append('digests', d);
  return request(`/api/query-history/trend?${params.toString()}`, { signal });
}
