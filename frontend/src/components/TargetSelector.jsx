import { navigate } from '../lib/router.js';

/**
 * Sidebar target list. Each row is a real navigation link to `/targets/:id`;
 * the caller passes the URL-derived `selected` id so the active highlight
 * stays in sync with the router. The "Fleet View" button at the top returns
 * to `/`. Settings is reachable from the top header's ⚙ button.
 */
export default function TargetSelector({ targets, labels, selected }) {
  return (
    <aside className="sidebar">
      <button
        type="button"
        className="sidebar__fleet"
        onClick={() => navigate('/')}
        title="Back to Fleet View"
      >
        ← Fleet View
      </button>

      <div className="sidebar__heading">Targets</div>
      {targets.length === 0 && (
        <div className="target__placeholder">
          No targets configured. Add one from Settings.
        </div>
      )}
      {targets.map((id) => {
        const label = labels && labels[id] ? labels[id] : id;
        return (
          <button
            key={id}
            type="button"
            className={`target ${id === selected ? 'target--active' : ''}`}
            onClick={() => navigate(`/targets/${encodeURIComponent(id)}`)}
            title={label === id ? id : `${label} (${id})`}
          >
            {label}
          </button>
        );
      })}
    </aside>
  );
}
