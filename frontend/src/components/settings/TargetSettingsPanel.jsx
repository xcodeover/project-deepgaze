import { useState, useCallback, useMemo } from 'react';
import {
  useTargetsStore,
  selectAllTargetList,
  selectTargetsStatus,
  selectTargetsError,
} from '../../store/targetsStore.js';
import TargetEditModal from '../TargetEditModal.jsx';

/**
 * Databases tab content. This was previously the whole "Fleet Settings" page;
 * lifting the header + route chrome into SettingsShell lets the same DnD
 * table live inside the new tabbed admin panel without duplicating layout.
 *
 * All CRUD goes through the existing targetsStore — the notifications tab
 * uses its own store-less form instead because a single-row config doesn't
 * need a store.
 */
export default function TargetSettingsPanel() {
  const list       = useTargetsStore(selectAllTargetList);
  const status     = useTargetsStore(selectTargetsStatus);
  const error      = useTargetsStore(selectTargetsError);
  const reorder    = useTargetsStore((s) => s.reorder);
  const remove     = useTargetsStore((s) => s.remove);
  const setEnabled = useTargetsStore((s) => s.setEnabled);

  const [editing, setEditing]     = useState(null); // null | 'new' | target object
  const [dragIndex, setDragIndex] = useState(null);
  const [hoverIndex, setHoverIndex] = useState(null);
  const [busy, setBusy]           = useState(false);
  const [message, setMessage]     = useState(null);

  const onDragStart = useCallback((idx) => (e) => {
    setDragIndex(idx);
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', String(idx));
  }, []);

  const onDragOver = useCallback((idx) => (e) => {
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
    if (hoverIndex !== idx) setHoverIndex(idx);
  }, [hoverIndex]);

  const onDragEnd = useCallback(() => {
    setDragIndex(null);
    setHoverIndex(null);
  }, []);

  const onDrop = useCallback((idx) => async (e) => {
    e.preventDefault();
    const from = dragIndex;
    setDragIndex(null);
    setHoverIndex(null);
    if (from == null || from === idx) return;
    const next = list.map((t) => t.id);
    const [moved] = next.splice(from, 1);
    next.splice(idx, 0, moved);
    setBusy(true);
    try {
      await reorder(next);
      setMessage({ kind: 'ok', text: 'Order saved.' });
    } catch (err) {
      setMessage({ kind: 'err', text: `Reorder failed: ${err.message || err}` });
    } finally {
      setBusy(false);
    }
  }, [dragIndex, list, reorder]);

  const handleToggleEnabled = useCallback(async (target) => {
    const next = !target.enabled;
    if (!next && !window.confirm(
      `Disable "${target.displayName}" (${target.id})?\n\n` +
      `Collection stops and pools close. You can re-enable it any time — the configuration is preserved.`
    )) return;
    setBusy(true);
    try {
      await setEnabled(target.id, next);
      setMessage({
        kind: 'ok',
        text: next ? `Enabled ${target.id}.` : `Disabled ${target.id} — collection stopped.`,
      });
    } catch (err) {
      setMessage({ kind: 'err', text: `Toggle failed: ${err.message || err}` });
    } finally {
      setBusy(false);
    }
  }, [setEnabled]);

  const handleDelete = useCallback(async (target) => {
    if (!window.confirm(
      `Delete target "${target.displayName}" (${target.id})?\n\n` +
      `This stops collection immediately and closes its connection pools.`)
    ) return;
    setBusy(true);
    try {
      await remove(target.id);
      setMessage({ kind: 'ok', text: `Deleted ${target.id}.` });
    } catch (err) {
      setMessage({ kind: 'err', text: `Delete failed: ${err.message || err}` });
    } finally {
      setBusy(false);
    }
  }, [remove]);

  const rows = useMemo(() => list.map((t, idx) => {
    const isEnabled = t.enabled !== false;
    return (
      <tr
        key={t.id}
        draggable
        onDragStart={onDragStart(idx)}
        onDragOver={onDragOver(idx)}
        onDrop={onDrop(idx)}
        onDragEnd={onDragEnd}
        className={[
          'tgt-row',
          dragIndex === idx ? 'tgt-row--dragging' : '',
          hoverIndex === idx && dragIndex !== null && dragIndex !== idx ? 'tgt-row--over' : '',
          !isEnabled ? 'tgt-row--off' : '',
        ].join(' ').trim()}
      >
        <td className="tgt-row__grip" aria-hidden="true">⋮⋮</td>
        <td className="tgt-row__name">
          <div className="tgt-row__name-primary">{t.displayName}</div>
          <div className="tgt-row__name-secondary">{t.id}</div>
        </td>
        <td>
          <span className={`tgt-badge tgt-badge--${(t.engine || '').toLowerCase()}`}>{t.engine || '—'}</span>
        </td>
        <td className="tgt-row__url" title={t.jdbcUrl}>{t.jdbcUrl}</td>
        <td>{t.pollIntervalMs} ms</td>
        <td>
          <label className="toggle toggle--sm" title={isEnabled ? 'Click to disable collection' : 'Click to resume collection'}>
            <input
              type="checkbox"
              checked={isEnabled}
              disabled={busy}
              onChange={() => handleToggleEnabled(t)}
            />
            <span className="toggle__slider" />
            <span className="toggle__label">{isEnabled ? 'On' : 'Off'}</span>
          </label>
        </td>
        <td className="tgt-row__actions">
          <button type="button" className="tgt-btn tgt-btn--ghost" onClick={() => setEditing(t)}>Edit</button>
          <button
            type="button"
            className="tgt-btn tgt-btn--danger"
            onClick={() => handleDelete(t)}
            disabled={busy}
          >Delete</button>
        </td>
      </tr>
    );
  }), [list, dragIndex, hoverIndex, busy, onDragStart, onDragOver, onDrop, onDragEnd, handleDelete, handleToggleEnabled]);

  return (
    <>
      <div className="settings__toolbar">
        <div>
          <h2 className="settings__title">Configured Targets</h2>
          <div className="settings__sub">
            Drag rows to reorder. Changes apply instantly and propagate to the sidebar.
          </div>
        </div>
        <button
          type="button"
          className="tgt-btn tgt-btn--primary"
          onClick={() => setEditing('new')}
        >+ Add Target</button>
      </div>

      {message && (
        <div className={`settings__msg settings__msg--${message.kind}`}>
          {message.text}
          <button
            type="button"
            className="settings__msg-close"
            onClick={() => setMessage(null)}
            aria-label="Dismiss"
          >×</button>
        </div>
      )}

      {status === 'loading' && list.length === 0 && (
        <div className="settings__empty">Loading targets…</div>
      )}
      {status === 'error' && (
        <div className="settings__empty settings__empty--error">
          Failed to load targets: {error}
        </div>
      )}
      {status === 'ready' && list.length === 0 && (
        <div className="settings__empty">
          No targets configured yet. Click <b>+ Add Target</b> to get started.
        </div>
      )}

      {list.length > 0 && (
        <div className="settings__tablewrap">
          <table className="settings__table">
            <thead>
              <tr>
                <th aria-label="Drag handle" />
                <th>Target</th>
                <th>Engine</th>
                <th>JDBC URL</th>
                <th>Poll</th>
                <th>Collection</th>
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>{rows}</tbody>
          </table>
        </div>
      )}

      {editing && (
        <TargetEditModal
          target={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={(saved, op) => {
            setEditing(null);
            setMessage({
              kind: 'ok',
              text: op === 'create' ? `Created ${saved.id}.` : `Updated ${saved.id}.`,
            });
          }}
        />
      )}
    </>
  );
}
