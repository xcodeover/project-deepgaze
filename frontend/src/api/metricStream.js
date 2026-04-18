/**
 * Pure JS wrapper around the browser EventSource. Knows nothing about React,
 * the store, or the UI. The store owns one instance and forwards events.
 *
 * Status state machine:
 *   idle → connecting → live ⇄ reconnecting    (browser auto-reconnects on error)
 *                            → disconnected     (only on explicit close())
 */
export class MetricStreamConnection {
  constructor({ url, onMessage, onStatus }) {
    this.url = url;
    this.onMessage = onMessage;
    this.onStatus = onStatus;
    this.es = null;
  }

  connect() {
    if (this.es) return;
    this.onStatus('connecting');
    this.es = new EventSource(this.url);

    this.es.addEventListener('open', () => this.onStatus('live'));

    // EventSource auto-reconnects on transport error; we only update status.
    this.es.addEventListener('error', () => {
      this.onStatus(this.es && this.es.readyState === EventSource.CLOSED
        ? 'disconnected'
        : 'reconnecting');
    });

    // Backend emits `event: metric`; we listen for that named event explicitly
    // so heartbeat comments and other future event types do not collide.
    this.es.addEventListener('metric', (ev) => {
      try {
        this.onMessage(JSON.parse(ev.data));
      } catch (err) {
        // Malformed frame — log once, do not propagate; the stream stays alive.
        console.warn('[metricStream] failed to parse SSE frame', err);
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
