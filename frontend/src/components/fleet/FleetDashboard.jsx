import { useMemo } from 'react';
import {
  useTargetsStore,
  selectTargetList,
  selectTargetsStatus,
} from '../../store/targetsStore.js';
import ConnectionStatus from '../ConnectionStatus.jsx';
import UserChip from '../UserChip.jsx';
import AlertBadge from '../AlertBadge.jsx';
import AlertToastHost from '../AlertToastHost.jsx';
import AlertHistoryPanel from '../AlertHistoryPanel.jsx';
import FleetSummaryBar from './FleetSummaryBar.jsx';
import TargetCard from './TargetCard.jsx';
import { navigate } from '../../lib/router.js';
import { useState, useCallback } from 'react';

/**
 * NOC-style wall-board. No sidebar, no per-target detail — just the fleet
 * at a glance. Clicking any card deep-links to /targets/:id for the full
 * bento view. The streams stay open at the App level so returning to this
 * view doesn't pay a reconnect cost.
 */
export default function FleetDashboard() {
  const list   = useTargetsStore(selectTargetList);
  const status = useTargetsStore(selectTargetsStatus);

  const [historyOpen, setHistoryOpen] = useState(false);

  const cards = useMemo(
    () => list.map((t) => <TargetCard key={t.id} target={t} />),
    [list],
  );

  const handleJumpToAlert = useCallback((ev) => {
    if (!ev?.targetId) return;
    navigate(`/targets/${encodeURIComponent(ev.targetId)}`);
    setHistoryOpen(false);
  }, []);

  return (
    <div className="app">
      <header className="app__header">
        <div className="app__title">
          DeepGaze
          <small>Fleet View</small>
        </div>
        <div className="app__header-right">
          <AlertBadge open={historyOpen} onToggle={() => setHistoryOpen((v) => !v)} />
          <button
            type="button"
            className="tgt-btn tgt-btn--ghost"
            onClick={() => navigate('/settings/databases')}
          >Settings</button>
          <ConnectionStatus />
          <UserChip />
        </div>
      </header>

      <AlertToastHost />
      <AlertHistoryPanel
        open={historyOpen}
        onClose={() => setHistoryOpen(false)}
        onJumpToAlert={handleJumpToAlert}
      />

      <main className="fleet">
        <FleetSummaryBar targets={list} />

        {status === 'loading' && list.length === 0 && (
          <div className="fleet__empty">Loading fleet…</div>
        )}
        {status === 'ready' && list.length === 0 && (
          <div className="fleet__empty">
            No targets configured. <a href="/settings/databases"
              onClick={(e) => { e.preventDefault(); navigate('/settings/databases'); }}>
              Add one in Settings →
            </a>
          </div>
        )}

        {list.length > 0 && (
          <div className="fleet-grid">{cards}</div>
        )}
      </main>
    </div>
  );
}
