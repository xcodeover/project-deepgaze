export default function TargetSelector({ targets, selected, onSelect }) {
  return (
    <aside className="sidebar">
      <div className="sidebar__heading">Targets</div>
      {targets.length === 0 && (
        <div className="target__placeholder">
          Waiting for first metric…
        </div>
      )}
      {targets.map((id) => (
        <button
          key={id}
          type="button"
          className={`target ${id === selected ? 'target--active' : ''}`}
          onClick={() => onSelect(id)}
        >
          {id}
        </button>
      ))}
    </aside>
  );
}
