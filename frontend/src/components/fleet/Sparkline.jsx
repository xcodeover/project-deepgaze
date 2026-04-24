import { useMemo } from 'react';

/**
 * Ultra-lean 60-point sparkline — no axes, no legend, no tooltips. One
 * polyline over a scaled viewBox, sized to the card footer so multiple
 * TargetCards on a wallboard render at ~60fps on a Raspberry Pi.
 *
 * The component is deliberately unopinionated about what's being plotted;
 * TargetCard feeds it the sequence (default: active-session counts over the
 * last 60 snapshots) and a stroke colour derived from the card's grade so
 * the spark matches the ring.
 */
const VBOX_W = 200;
const VBOX_H = 40;
const PAD    = 2;

export default function Sparkline({ values, colour = '#4ea1ff' }) {
  const d = useMemo(() => pathFor(values), [values]);

  if (!d) {
    return <div className="sparkline sparkline--empty">—</div>;
  }

  return (
    <svg
      className="sparkline"
      viewBox={`0 0 ${VBOX_W} ${VBOX_H}`}
      preserveAspectRatio="none"
      aria-hidden="true"
    >
      {/* Faint fill beneath the line so the sparkline reads as a gauge, not
          just a squiggle. Uses the stroke colour at low opacity — keeps
          card compositing simple. */}
      <path d={`${d} L ${VBOX_W - PAD} ${VBOX_H - PAD} L ${PAD} ${VBOX_H - PAD} Z`}
            fill={colour} fillOpacity="0.12" stroke="none" />
      <path d={d} fill="none" stroke={colour} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

function pathFor(values) {
  if (!values || values.length < 2) return null;
  const nums = values.map(Number).filter(Number.isFinite);
  if (nums.length < 2) return null;

  const min = Math.min(...nums);
  const max = Math.max(...nums);
  const span = max - min || 1;
  const stepX = (VBOX_W - PAD * 2) / (nums.length - 1);

  let d = '';
  nums.forEach((v, i) => {
    const x = PAD + i * stepX;
    // Normalised y: 0 top, 1 bottom — we invert so higher values rise up.
    const y = VBOX_H - PAD - ((v - min) / span) * (VBOX_H - PAD * 2);
    d += (i === 0 ? 'M ' : ' L ') + x.toFixed(1) + ' ' + y.toFixed(1);
  });
  return d;
}
