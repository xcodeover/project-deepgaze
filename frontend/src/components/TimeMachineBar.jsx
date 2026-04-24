import { useEffect, useMemo, useRef, useState } from 'react';
import {
  useMetricsStore,
  selectReplayState,
  selectReplayByKey,
  selectTargetNames,
} from '../store/metricsStore.js';
import {
  useAlertsStore,
  selectFiring,
  selectRecentEvents,
} from '../store/alertsStore.js';
import { fetchRange } from '../api/history.js';
import {
  buildPostMortemMarkdown,
  downloadMarkdown,
  postMortemFilename,
} from '../lib/postmortem.js';

/**
 * Timeline Scrubber — the bottom-of-dashboard "Time Machine Bar". The slider
 * spans the available SQLite history for the selected target (may be shorter
 * than the configured retention during the first hours after boot).
 *
 * Interaction model:
 *   - Dragging the slider enters replay mode and fetches the point-in-time
 *     snapshot. Live SSE ingestion is paused while replay is active (the
 *     EventSource itself stays connected — the store just drops frames).
 *   - "Return to Live" re-enables ingestion and rehydrates from /api/buffer.
 *   - A pulsing red dot on the Return-to-Live button signals that you are
 *     viewing history; when the dot is gone you are on the live stream.
 *
 * Scrubber input is debounced (~200ms) so a fast drag only fires one or two
 * network requests, not one per intermediate value.
 */
export default function TimeMachineBar({ targetId }) {
  const replay = useMetricsStore(selectReplayState);
  const enterReplay = useMetricsStore((s) => s.enterReplay);
  const exitReplay  = useMetricsStore((s) => s.exitReplay);
  const replayByKey = useMetricsStore(selectReplayByKey);
  const targetNames = useMetricsStore(selectTargetNames);
  const firingAlerts = useAlertsStore(selectFiring);
  const recentEvents = useAlertsStore(selectRecentEvents);

  const [bounds, setBounds] = useState({ minMs: 0, maxMs: 0, hasData: false });
  const [sliderMs, setSliderMs] = useState(0);
  const debounceRef = useRef(null);
  const boundsRef = useRef(bounds);
  useEffect(() => { boundsRef.current = bounds; }, [bounds]);

  // Switching targets while replaying would leave the dashboard showing a
  // different target's frozen snapshot — exit replay on target change.
  useEffect(() => {
    if (replay.active && replay.targetId && replay.targetId !== targetId) {
      exitReplay();
    }
  }, [targetId, replay.active, replay.targetId, exitReplay]);

  // Pull range bounds when target changes or when replay toggles off — the
  // live stream extends the upper bound continuously so we refresh on exit.
  useEffect(() => {
    if (!targetId) return;
    let cancelled = false;
    fetchRange(targetId).then((r) => {
      if (cancelled) return;
      setBounds({ minMs: r.rangeMinMs, maxMs: r.rangeMaxMs, hasData: r.hasData });
      setSliderMs(r.rangeMaxMs || Date.now());
    }).catch(() => {});
    return () => { cancelled = true; };
  }, [targetId, replay.active]);

  // While on the live stream, the slider thumb tracks "now" (the upper-right
  // edge). When the user grabs it, we freeze and flip to replay. Reads latest
  // bounds from `boundsRef` so the effect only re-subscribes on replay toggle
  // — previously `bounds.maxMs` in deps caused the interval to rebuild every
  // second (thousands of timer churn events per hour of live viewing).
  useEffect(() => {
    if (replay.active) return;
    const id = setInterval(() => {
      setBounds((b) => (b.hasData ? { ...b, maxMs: Math.max(b.maxMs, Date.now()) } : b));
      setSliderMs((v) => {
        const b = boundsRef.current;
        return b.hasData ? Math.max(v, b.maxMs) : v;
      });
    }, 1000);
    return () => clearInterval(id);
  }, [replay.active]);

  // Clear any pending debounce so the scrub doesn't fire enterReplay after
  // the component has unmounted (e.g. user navigated away during a drag).
  useEffect(() => () => {
    if (debounceRef.current) clearTimeout(debounceRef.current);
  }, []);

  const onExportPostMortem = () => {
    if (!replay.active || replay.loading) return;
    const md = buildPostMortemMarkdown({
      targetId: targetId,
      targetName: targetNames[targetId],
      asOfMs: replay.asOfMs,
      rangeMinMs: replay.rangeMinMs,
      rangeMaxMs: replay.rangeMaxMs,
      replayByKey,
      firingAlerts,
      recentEvents,
    });
    downloadMarkdown(postMortemFilename(targetId, replay.asOfMs), md);
  };

  const onScrub = (valueMs) => {
    setSliderMs(valueMs);
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => {
      enterReplay(targetId, valueMs);
    }, 180);
  };

  const windowLabel = useMemo(() => formatWindow(bounds.minMs, bounds.maxMs), [bounds]);
  const scrubLabel  = useMemo(() => formatAbsolute(sliderMs), [sliderMs]);

  if (!targetId || !bounds.hasData) {
    return (
      <div className="timemachine timemachine--empty" aria-label="Time machine">
        <span className="timemachine__status timemachine__status--idle">
          <span className="timemachine__dot" /> Live
        </span>
        <span className="timemachine__placeholder">No history persisted yet — give the writer a moment.</span>
      </div>
    );
  }

  return (
    <div className={`timemachine ${replay.active ? 'timemachine--replay' : 'timemachine--live'}`}
         role="region" aria-label="Time machine">
      <span className={`timemachine__status ${replay.active ? 'timemachine__status--replay' : 'timemachine__status--live'}`}>
        <span className={`timemachine__dot ${replay.active ? 'timemachine__dot--pulse' : ''}`} />
        {replay.active ? 'REPLAY' : 'LIVE'}
      </span>

      <div className="timemachine__slider-wrap">
        <div className="timemachine__scale">
          <span>{formatAbsolute(bounds.minMs)}</span>
          <span>{windowLabel}</span>
          <span>{formatAbsolute(bounds.maxMs)}</span>
        </div>
        <input
          type="range"
          className="timemachine__slider"
          min={bounds.minMs}
          max={bounds.maxMs}
          step={1000}
          value={Math.min(Math.max(sliderMs, bounds.minMs), bounds.maxMs)}
          onChange={(e) => onScrub(Number(e.target.value))}
          aria-label="Scrub timeline"
        />
        <div className="timemachine__caret">
          <span className="timemachine__caret-label">
            {replay.active ? scrubLabel : 'at live edge'}
            {replay.loading && <span className="timemachine__loading">…</span>}
          </span>
        </div>
      </div>

      {replay.active && (
        <div className="timemachine__actions">
          <button
            type="button"
            className="timemachine__export-btn"
            onClick={onExportPostMortem}
            disabled={replay.loading}
            aria-label="Export post-mortem as Markdown"
            title="Download a Markdown incident report for this frozen snapshot"
          >
            ⤓ Export Post-Mortem
          </button>
          <button
            type="button"
            className="timemachine__live-btn"
            onClick={() => exitReplay()}
            aria-label="Return to live stream"
          >
            <span className="timemachine__dot timemachine__dot--pulse timemachine__dot--red" />
            Return to Live
          </button>
        </div>
      )}
    </div>
  );
}

function formatAbsolute(ms) {
  if (!ms) return '—';
  const d = new Date(ms);
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  const ss = String(d.getSeconds()).padStart(2, '0');
  return `${hh}:${mm}:${ss}`;
}

function formatWindow(minMs, maxMs) {
  if (!minMs || !maxMs || maxMs <= minMs) return '';
  const spanSec = Math.round((maxMs - minMs) / 1000);
  if (spanSec < 60)      return `${spanSec}s window`;
  if (spanSec < 3600)    return `${Math.round(spanSec / 60)}m window`;
  const h = Math.floor(spanSec / 3600);
  const m = Math.round((spanSec - h * 3600) / 60);
  return m === 0 ? `${h}h window` : `${h}h ${m}m window`;
}
