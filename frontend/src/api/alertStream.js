/**
 * Thin EventSource wrapper for the alert transition stream. Symmetric with
 * metricStream.js so the store-side lifecycle code stays identical.
 */
export class AlertStreamConnection {
  constructor({ url, onEvent, onStatus }) {
    this.url = url;
    this.onEvent = onEvent;
    this.onStatus = onStatus;
    this.es = null;
  }

  connect() {
    if (this.es) return;
    this.onStatus('connecting');
    this.es = new EventSource(this.url);

    this.es.addEventListener('open', () => this.onStatus('live'));

    this.es.addEventListener('error', () => {
      this.onStatus(this.es && this.es.readyState === EventSource.CLOSED
        ? 'disconnected'
        : 'reconnecting');
    });

    this.es.addEventListener('alert', (ev) => {
      try {
        this.onEvent(JSON.parse(ev.data));
      } catch (err) {
        console.warn('[alertStream] failed to parse SSE frame', err);
      }
    });
  }

  disconnect() {
    if (!this.es) return;
    this.es.close();
    this.es = null;
    this.onStatus('disconnected');
  }
}
