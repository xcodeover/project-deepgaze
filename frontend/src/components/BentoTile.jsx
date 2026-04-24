/**
 * Single cell in the dashboard bento grid. Always renders — if content is
 * absent we show a skeleton so the 3x3 layout is pixel-stable regardless of
 * which engine is selected. Callers pass one of:
 *
 *   empty        children render as usual
 *   unsupported  "Not Supported on <engine>" pill, tile greys out
 *   loading      animated skeleton lines, used during the first few snapshots
 *
 * When none of those are set we just render `children`. The spatial muscle
 * memory of the operator must never be broken — every tile always occupies
 * its grid area.
 */
export default function BentoTile({
  area,
  title,
  subtitle,
  actions,
  scroll = false,
  status = 'ok',         // 'ok' | 'loading' | 'empty' | 'unsupported'
  statusHint,            // short message rendered in skeleton states
  children,
}) {
  const style = area ? { gridArea: area } : undefined;
  const bodyCls = `bento-tile__body ${scroll ? 'bento-tile__body--scroll' : 'bento-tile__body--fill'}`;

  return (
    <section className={`bento-tile bento-tile--${status}`} style={style} aria-label={title}>
      {(title || actions) && (
        <header className="bento-tile__header">
          <div className="bento-tile__titles">
            {title ? <h3 className="bento-tile__title">{title}</h3> : null}
            {subtitle ? <div className="bento-tile__subtitle">{subtitle}</div> : null}
          </div>
          {actions ? <div className="bento-tile__actions">{actions}</div> : null}
        </header>
      )}
      <div className={bodyCls}>
        {status === 'ok' && children}
        {status === 'loading' && <SkeletonLoading hint={statusHint} />}
        {status === 'empty' && <SkeletonEmpty hint={statusHint} />}
        {status === 'unsupported' && <SkeletonUnsupported hint={statusHint} />}
      </div>
    </section>
  );
}

function SkeletonLoading({ hint }) {
  return (
    <div className="bento-skel">
      <div className="bento-skel__bar bento-skel__bar--a" />
      <div className="bento-skel__bar bento-skel__bar--b" />
      <div className="bento-skel__bar bento-skel__bar--c" />
      <div className="bento-skel__hint">{hint || 'Collecting first snapshot…'}</div>
    </div>
  );
}

function SkeletonEmpty({ hint }) {
  return (
    <div className="bento-skel bento-skel--empty">
      <div className="bento-skel__dot" aria-hidden="true" />
      <div className="bento-skel__hint">{hint || 'Awaiting data'}</div>
    </div>
  );
}

function SkeletonUnsupported({ hint }) {
  return (
    <div className="bento-skel bento-skel--unsupported">
      <div className="bento-skel__tag">NOT SUPPORTED</div>
      <div className="bento-skel__hint">{hint || 'This metric is not available on the selected engine.'}</div>
    </div>
  );
}
