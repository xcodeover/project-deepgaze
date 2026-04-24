import { useEffect } from 'react';
import { useAlertsStore } from '../store/alertsStore.js';

/**
 * Mirror of useMetricStream: hydrate the current firing set from REST, then
 * subscribe to live transitions via SSE. One mount near the app root.
 */
export function useAlertStream(streamUrl = '/api/stream/alerts', firingUrl = '/api/alerts/firing') {
  const bootstrap  = useAlertsStore((s) => s.bootstrap);
  const connect    = useAlertsStore((s) => s.connect);
  const disconnect = useAlertsStore((s) => s.disconnect);

  useEffect(() => {
    let cancelled = false;
    bootstrap(firingUrl).then(() => {
      if (!cancelled) connect(streamUrl);
    });
    return () => {
      cancelled = true;
      disconnect();
    };
  }, [streamUrl, firingUrl, bootstrap, connect, disconnect]);
}
