import { useEffect } from 'react';
import { useMetricsStore } from '../store/metricsStore.js';

/**
 * Mount/unmount glue between React and the store-owned SSE connection.
 * Hydrates history from /api/buffer first so reloading the page preserves
 * the rolling window, then opens the live SSE stream.
 */
export function useMetricStream(streamUrl = '/api/stream/metrics', bufferUrl = '/api/buffer') {
  const bootstrap  = useMetricsStore((s) => s.bootstrap);
  const connect    = useMetricsStore((s) => s.connect);
  const disconnect = useMetricsStore((s) => s.disconnect);

  useEffect(() => {
    let cancelled = false;
    bootstrap(bufferUrl).then(() => {
      if (!cancelled) connect(streamUrl);
    });
    return () => {
      cancelled = true;
      disconnect();
    };
  }, [streamUrl, bufferUrl, bootstrap, connect, disconnect]);
}
