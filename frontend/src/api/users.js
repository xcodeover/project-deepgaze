/**
 * REST client for /api/users/** — admin user management plus the
 * self-service password change. Every call funnels through apiJson so
 * the JWT header is attached and a 401 rebounces to /login.
 *
 * Response shape is the backend UserDto: {id, username, role, enabled,
 * createdAt, updatedAt}. Password hashes never cross the wire.
 */

import { apiJson as request } from './http.js';

export function fetchUsers() {
  return request('/api/users');
}

export function createUser({ username, password, role }) {
  return request('/api/users', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password, role }),
  });
}

export function deleteUser(id) {
  return request(`/api/users/${encodeURIComponent(id)}`, {
    method: 'DELETE',
  });
}

export function resetUserPassword(id, newPassword) {
  return request(`/api/users/${encodeURIComponent(id)}/password`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ newPassword }),
  });
}

export function setUserRole(id, role) {
  return request(`/api/users/${encodeURIComponent(id)}/role`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ role }),
  });
}

export function setUserEnabled(id, enabled) {
  return request(`/api/users/${encodeURIComponent(id)}/enabled`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ enabled: !!enabled }),
  });
}

/** Self-service — any authenticated user can rotate their OWN password. */
export function changeMyPassword(currentPassword, newPassword) {
  return request('/api/users/me/password', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ currentPassword, newPassword }),
  });
}
