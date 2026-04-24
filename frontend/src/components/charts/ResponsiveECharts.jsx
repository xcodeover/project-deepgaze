import { useEffect, useRef } from 'react';
import ReactECharts from 'echarts-for-react';

/**
 * Drop-in replacement for `<ReactECharts>` that also resizes on *container*
 * size changes, not just window resizes.
 *
 * echarts-for-react's autoResize only listens on `window.resize`. In our
 * NOC grid, tiles can change size without the window changing — the alert
 * banner appearing shortens the tile grid, the drawer taking 640px narrows
 * the main column, a future split-pane move redistributes cells. When that
 * happens the chart canvas keeps its old pixel dimensions, overflows the
 * cell, and breaks the `minmax(0, 1fr)` track.
 *
 * ResizeObserver on the chart DOM fires whenever the element's border-box
 * changes for any reason. We coalesce bursts with requestAnimationFrame so
 * a drag-resize doesn't queue one resize() per observer callback.
 */
export default function ResponsiveECharts(props) {
  const ref = useRef(null);

  useEffect(() => {
    const wrapper = ref.current;
    if (!wrapper) return;

    const inst = wrapper.getEchartsInstance();
    const dom  = inst.getDom();
    if (!dom) return;

    let rafId = 0;
    const ro = new ResizeObserver(() => {
      if (rafId) cancelAnimationFrame(rafId);
      rafId = requestAnimationFrame(() => inst.resize());
    });
    ro.observe(dom);

    return () => {
      ro.disconnect();
      if (rafId) cancelAnimationFrame(rafId);
    };
  }, []);

  return <ReactECharts ref={ref} {...props} />;
}
