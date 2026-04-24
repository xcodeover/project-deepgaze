/**
 * Fetch wrappers for the target control-plane (/api/targets/**). Every call
 * returns JSON-parsed body on success and throws an Error whose message is
 * the server's response text on failure — the settings page surfaces those
 * messages inline rather than via a toast.
 *
 * JWT auth is injected by apiJson (see api/http.js): the Bearer header gets
 * attached automatically and a 401 drops the session + bounces to /login.
 */

import { apiJson as request } from './http.js';

export function fetchTargets() {
  return request('/api/targets');
}

export function fetchTarget(id) {
  return request(`/api/targets/${encodeURIComponent(id)}`);
}

export function createTarget(dto) {
  return request('/api/targets', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(dto),
  });
}

export function updateTarget(id, dto) {
  return request(`/api/targets/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(dto),
  });
}

export function deleteTarget(id) {
  return request(`/api/targets/${encodeURIComponent(id)}`, {
    method: 'DELETE',
  });
}

/**
 * Flip the runtime on/off flag. Disabling evicts the target from pools and
 * the scheduler; enabling re-hydrates it. Payload is just `{enabled}`.
 */
export function setTargetEnabled(id, enabled) {
  return request(`/api/targets/${encodeURIComponent(id)}/enabled`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ enabled: !!enabled }),
  });
}

/**
 * Bulk persist display order. The backend wraps the entries in a
 * `ReorderRequest` record — the wire shape is `{entries:[{id, displayOrder}]}`.
 */
export function reorderTargets(entries) {
  return request('/api/targets/reorder', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ entries }),
  });
}

/**
 * Transient probe — opens a throwaway Hikari pool + connection. Returns
 * `{ok, message, latencyMs}` on both success and typed failure; the only
 * way this throws is a transport/5xx error.
 */
export function testConnection(dto) {
  return request('/api/targets/test-connection', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(dto),
  });
}
