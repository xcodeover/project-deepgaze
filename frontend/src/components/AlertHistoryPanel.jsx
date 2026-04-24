import { useEffect } from 'react';
import { useAlertsStore, selectRecentEvents, selectFiring } from '../store/alertsStore.js';

/**
 * Right-side drawer rendering the alerts store's rolling `recent[]` log as a
 * vertical timeline. Open/close is owned by the parent (Dashboard) so the
 * AlertBadge click handler can toggle it without duplicating state.
 *
 * The timeline is purely passive — it mirrors store state, so a store update
 * from an SSE event repaints the drawer in real time while it's open.
 *
 * Alert-to-Replay bridge: each event is a button that, when clicked, invokes
 * {@code onJumpToAlert(ev)} — Dashboard uses that to switch the selected
 * target (if needed) and enter V9 replay mode at the event's timestamp,
 * delivering one-click post-mortems.
 */
export default function AlertHistoryPanel({ open, onClose, onJumpToAlert }) {
  const events = useAlertsStore(selectRecentEvents);
  const firing = useAlertsStore(selectFiring);

  useEffect(() => {
    if (!open) return;
    const onKey = (e) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  if (!open) return null;

  const firingCount = firing.length;

  return (
    <>
      <div className="drawer__scrim" onClick={onClose} />
      <aside className="history-drawer" role="dialog" aria-label="Alert history">
        <div className="history-drawer__header">
          <div>
            <div className="history-drawer__title">Alert History</div>
            <div className="history-drawer__sub">
              {firingCount > 0
                ? `${firingCount} firing · ${events.length} recent event${events.length === 1 ? '' : 's'}`
                : `${events.length} recent event${events.length === 1 ? '' : 's'}`}
            </div>
          </div>
          <button className="history-drawer__close" onClick={onClose} aria-label="Close">×</button>
        </div>
        <div className="history-drawer__body">
          {events.length === 0
            ? <div className="history-empty">No alert activity yet. Events will appear here as rules fire and resolve.</div>
            : <Timeline events={events} onJumpToAlert={onJumpToAlert} />
          }
        </div>
      </aside>
    </>
  );
}

function Timeline({ events, onJumpToAlert }) {
  return (
    <ol className="timeline">
      {events.map((ev, idx) => (
        <TimelineItem key={keyFor(ev, idx)} ev={ev} onJumpToAlert={onJumpToAlert} />
      ))}
    </ol>
  );
}

function TimelineItem({ ev, onJumpToAlert }) {
  const kind = (ev.kind || '').toUpperCase();
  const isResolved = kind === 'RESOLVED';
  const tone = isResolved ? 'resolved' : severityTone(ev.severity);

  // Only events with a parseable timestamp + targetId can jump — the
  // replay endpoint needs both. We render the non-jumpable case as a
  // plain <li> so the cursor/hover affordance matches reality.
  const canJump = !!onJumpToAlert && !!ev.targetId && canParseTs(ev.timestamp);

  const content = (
    <>
      <span className="timeline__dot" aria-hidden="true" />
      <div className="timeline__head">
        <span className="timeline__rule">{ev.ruleId}</span>
        <span className={`timeline__kind timeline__kind--${isResolved ? 'resolved' : 'fired'}`}>
          {isResolved ? 'RESOLVED' : (ev.severity || 'FIRED')}
        </span>
        <span className="timeline__target">{ev.targetId}</span>
        <span className="timeline__time">{formatTime(ev.timestamp)}</span>
        {canJump && <span className="timeline__replay-hint" aria-hidden="true">↺ Replay</span>}
      </div>
      {ev.message && <div className="timeline__msg">{ev.message}</div>}
      {ev.value != null && !Number.isNaN(ev.value) && (
        <div className="timeline__meta">value <span className="timeline__value">{formatValue(ev.value)}</span></div>
      )}
    </>
  );

  if (!canJump) {
    return <li className={`timeline__item timeline__item--${tone}`}>{content}</li>;
  }
  return (
    <li className={`timeline__item timeline__item--${tone} timeline__item--jumpable`}>
      <button
        type="button"
        className="timeline__jump"
        onClick={() => onJumpToAlert(ev)}
        aria-label={`Replay dashboard at ${formatTime(ev.timestamp)} when ${ev.ruleId} fired`}
      >
        {content}
      </button>
    </li>
  );
}

function canParseTs(iso) {
  if (!iso) return false;
  const t = Date.parse(iso);
  return !Number.isNaN(t);
}

function severityTone(sev) {
  const s = (sev || '').toUpperCase();
  if (s === 'CRITICAL') return 'critical';
  if (s === 'WARNING')  return 'warning';
  return 'info';
}

function formatValue(v) {
  if (Number.isInteger(v)) return String(v);
  return Number(v).toPrecision(4);
}

function formatTime(iso) {
  if (!iso) return '';
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return iso;
  const delta = Date.now() - t;
  if (delta < 60_000)    return `${Math.max(1, Math.round(delta / 1000))}s ago`;
  if (delta < 3_600_000) return `${Math.round(delta / 60_000)}m ago`;
  if (delta < 86_400_000) return `${Math.round(delta / 3_600_000)}h ago`;
  return new Date(t).toLocaleString();
}

function keyFor(ev, idx) {
  return `${ev.ruleId || 'x'}::${ev.targetId || 'x'}::${ev.timestamp || idx}::${ev.kind || ''}`;
}
