import { useAlertsStore, selectFiring } from '../store/alertsStore.js';

/**
 * Clickable pill in the header. Invisible when nothing is firing; otherwise
 * shows a pulsing severity-toned dot plus a firing count, and toggles the
 * alert-history drawer when clicked. Open state is owned by Dashboard so
 * other UI can react to it.
 */
export default function AlertBadge({ open, onToggle }) {
  const firing = useAlertsStore(selectFiring);
  if (firing.length === 0) return null;

  const hasCritical = firing.some((a) => a.severity === 'CRITICAL');
  const hasWarning  = firing.some((a) => a.severity === 'WARNING');
  const tone = hasCritical ? 'critical' : hasWarning ? 'warning' : 'info';

  return (
    <button
      type="button"
      className={`alert-badge alert-badge--${tone}${open ? ' alert-badge--open' : ''}`}
      onClick={onToggle}
      aria-expanded={open ? 'true' : 'false'}
      aria-label={`${firing.length} firing alert${firing.length === 1 ? '' : 's'} — open history`}
      title={`${firing.length} firing alert${firing.length === 1 ? '' : 's'} — click for history`}
    >
      <span className="alert-badge__dot" aria-hidden="true" />
      <span className="alert-badge__label">{firing.length} firing</span>
    </button>
  );
}
