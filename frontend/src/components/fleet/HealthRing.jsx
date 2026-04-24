/**
 * Pure SVG health ring — a 3-band traffic-light gauge sized for dense NOC
 * tiles. Drawn with a single arc so the colour fill smoothly follows the
 * score rather than snapping at the band boundaries; the underlying
 * threshold colour is picked from `grade` (ok / warn / crit) so tooltips
 * and card borders stay visually consistent with the ring.
 *
 * We render as SVG (not Canvas) so the ring composes correctly with the
 * page's accessibility tree and CSS animations (the wobble on score
 * change). Stroke-dasharray trick: the ring path is sized to
 * circumference(2πr); `strokeDashoffset` reveals the filled portion.
 */
const SIZE = 120;
const STROKE = 10;
const RADIUS = (SIZE - STROKE) / 2;
const CIRC = 2 * Math.PI * RADIUS;

const COLOURS = {
  ok:   '#2dd4a4',
  warn: '#f5b431',
  crit: '#ef5a5a',
};

export default function HealthRing({ score, grade, label }) {
  const pct = Math.max(0, Math.min(100, score)) / 100;
  const dash = CIRC * pct;
  const colour = COLOURS[grade] || COLOURS.ok;

  return (
    <svg
      className="health-ring"
      width={SIZE}
      height={SIZE}
      viewBox={`0 0 ${SIZE} ${SIZE}`}
      role="img"
      aria-label={label || `Health ${score} / 100`}
    >
      {/* Track */}
      <circle
        cx={SIZE / 2}
        cy={SIZE / 2}
        r={RADIUS}
        fill="none"
        stroke="#222838"
        strokeWidth={STROKE}
      />
      {/* Filled arc — rotated -90° so progress starts at 12 o'clock. */}
      <circle
        cx={SIZE / 2}
        cy={SIZE / 2}
        r={RADIUS}
        fill="none"
        stroke={colour}
        strokeWidth={STROKE}
        strokeLinecap="round"
        strokeDasharray={`${dash} ${CIRC - dash}`}
        transform={`rotate(-90 ${SIZE / 2} ${SIZE / 2})`}
        style={{ transition: 'stroke-dasharray 400ms ease, stroke 200ms linear' }}
      />
      <text
        x="50%"
        y="50%"
        textAnchor="middle"
        dominantBaseline="central"
        className="health-ring__num"
        fill={colour}
      >
        {score}
      </text>
      <text
        x="50%"
        y="68%"
        textAnchor="middle"
        dominantBaseline="central"
        className="health-ring__unit"
        fill="#8a93a6"
      >
        / 100
      </text>
    </svg>
  );
}
