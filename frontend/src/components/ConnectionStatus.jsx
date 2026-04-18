import { useMetricsStore, selectStatus } from '../store/metricsStore.js';

const LABELS = {
  idle:          'Idle',
  connecting:    'Connecting…',
  live:          'Live',
  reconnecting:  'Reconnecting…',
  disconnected:  'Disconnected',
};

const ICONS = {
  idle:          '⚪',
  connecting:    '🟡',
  live:          '🟢',
  reconnecting:  '🟡',
  disconnected:  '🔴',
};

export default function ConnectionStatus() {
  const status = useMetricsStore(selectStatus);

  return (
    <span className={`status status--${status}`} title={`SSE stream: ${status}`}>
      <span className="status__dot" />
      <span>{ICONS[status]} {LABELS[status] ?? status}</span>
    </span>
  );
}
