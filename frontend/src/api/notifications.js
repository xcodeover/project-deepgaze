/**
 * Fetch wrappers for /api/settings/notifications. Matches the shape used by
 * api/targets.js so the rest of the UI can treat this as yet another
 * control-plane resource.
 *
 * `telegramBotToken` is a write-only field — the GET response never carries
 * it, and PUT should omit it when the user hasn't retyped it so the backend
 * keeps the previously stored value.
 *
 * JWT auth is injected via api/http.js — see api/targets.js for the rationale.
 */

import { apiFetch, apiJson as request } from './http.js';

export function fetchNotificationSettings() {
  return request('/api/settings/notifications');
}

export function updateNotificationSettings(dto) {
  return request('/api/settings/notifications', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(dto),
  });
}

/**
 * One-shot probe. `dto` may contain a live (unsaved) token + chat id — the
 * backend uses whichever fields are present and falls back to the stored
 * config for missing ones. Returns `{ok, message}` on 2xx AND 4xx so the
 * UI can always render the server's reason.
 */
export async function sendTestNotification(dto) {
  const res = await apiFetch('/api/settings/notifications/test', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(dto || {}),
  });
  try {
    const body = await res.json();
    if (body && typeof body.ok === 'boolean') return body;
  } catch { /* non-JSON, fall through */ }
  return { ok: res.ok, message: res.ok ? 'Delivered.' : `HTTP ${res.status}` };
}
