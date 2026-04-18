import { useEffect } from 'react';
import { useMetricsStore } from '../store/metricsStore.js';

/**
 * Mount/unmount glue between React and the store-owned SSE connection.
 * Components call this once near the root; it has no return value.
 */
export function useMetricStream(url = '/api/stream/metrics') {
  const connect    = useMetricsStore((s) => s.connect);
  const disconnect = useMetricsStore((s) => s.disconnect);

  useEffect(() => {
    connect(url);
    return () => disconnect();
  }, [url, connect, disconnect]);
}
