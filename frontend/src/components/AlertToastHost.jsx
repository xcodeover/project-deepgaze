import { useCallback, useEffect, useRef, useState } from 'react';
import { useAlertsStore, selectRecentEvents } from '../store/alertsStore.js';

const TOAST_DURATION_MS = 8000;   // auto-dismiss after this long
const TOAST_EXIT_MS     = 200;    // must match the CSS exit animation
const MAX_VISIBLE_TOASTS = 5;     // older toasts drop off the stack

/**
 * Renders transient alert toasts, driven by the alerts store's event log.
 * A toast appears for every new FIRED/RESOLVED event that arrives while the
 * dashboard is open; events hydrated from REST on bootstrap are suppressed.
 *
 * Auto-dismiss timers are tracked in a ref keyed by toast id so each toast
 * has its own independent lifecycle. (Previously the single effect-scoped
 * timer was cancelled by the next event's cleanup, stranding older toasts.)
 */
export default function AlertToastHost() {
  const events = useAlertsStore(selectRecentEvents);
  const [toasts, setToasts] = useState([]);
  const baselineRef = useRef(null);
  const autoTimers   = useRef(new Map());    // id → setTimeout handle (auto-dismiss)
  const exitTimers   = useRef(new Map());    // id → setTimeout handle (post-exit removal)

  const dismiss = useCallback((id) => {
    const t = autoTimers.current.get(id);
    if (t) { clearTimeout(t); autoTimers.current.delete(id); }
    setToasts((prev) => prev.map((x) => (x.id === id ? { ...x, leaving: true } : x)));
    const ex = setTimeout(() => {
      exitTimers.current.delete(id);
      setToasts((prev) => prev.filter((x) => x.id !== id));
    }, TOAST_EXIT_MS);
    exitTimers.current.set(id, ex);
  }, []);

  useEffect(() => {
    // Snapshot existing log on first render so bootstrap events don't blast
    // a wall of toasts.
    if (baselineRef.current === null) {
      baselineRef.current = events.length > 0 ? events[0].timestamp : '';
      return;
    }
    if (events.length === 0) return;
    const latest = events[0];
    if (!latest.timestamp || latest.timestamp === baselineRef.current) return;
    baselineRef.current = latest.timestamp;

    const id = `${latest.ruleId}::${latest.targetId}::${latest.timestamp}`;
    if (autoTimers.current.has(id)) return;    // already displayed

    setToasts((prev) => {
      if (prev.some((t) => t.id === id)) return prev;
      return [{ id, event: latest, leaving: false }, ...prev].slice(0, MAX_VISIBLE_TOASTS);
    });

    const t = setTimeout(() => {
      autoTimers.current.delete(id);
      dismiss(id);
    }, TOAST_DURATION_MS);
    autoTimers.current.set(id, t);
  }, [events, dismiss]);

  // Clear all timers on unmount so nothing fires after teardown.
  useEffect(() => () => {
    for (const t of autoTimers.current.values()) clearTimeout(t);
    for (const t of exitTimers.current.values()) clearTimeout(t);
    autoTimers.current.clear();
    exitTimers.current.clear();
  }, []);

  if (toasts.length === 0) return null;

  return (
    <div className="toast-host" aria-live="polite">
      {toasts.map((t) => (
        <Toast key={t.id} toast={t} onClose={() => dismiss(t.id)} />
      ))}
    </div>
  );
}

function Toast({ toast, onClose }) {
  const { event, leaving } = toast;
  const isResolved = event.kind === 'RESOLVED';
  const tone = isResolved ? 'resolved' : severityTone(event.severity);
  const iconChar = isResolved ? '✓' : tone === 'critical' ? '!' : tone === 'warning' ? '!' : 'i';
  const title = isResolved ? 'Resolved' : (event.severity || 'INFO');

  return (
    <div className={`toast toast--${tone}${leaving ? ' toast--leaving' : ''}`} role="status">
      <div className="toast__icon" aria-hidden="true">{iconChar}</div>
      <div className="toast__body">
        <div className="toast__title">
          <span>{title} · {event.ruleId}</span>
          <span className="toast__target">{event.targetId}</span>
        </div>
        <div className="toast__msg">{event.message || (isResolved ? 'Condition cleared' : '—')}</div>
      </div>
      <button className="toast__close" onClick={onClose} aria-label="Dismiss">×</button>
    </div>
  );
}

function severityTone(sev) {
  const s = (sev || '').toUpperCase();
  if (s === 'CRITICAL') return 'critical';
  if (s === 'WARNING')  return 'warning';
  return 'info';
}
