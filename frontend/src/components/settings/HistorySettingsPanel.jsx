import { useEffect, useState } from 'react';
import { fetchHistorySettings, updateHistorySettings } from '../../api/history.js';

/**
 * Time Machine retention knob. Single number input clamped server-side to
 * [minHours, maxHours] — we surface those bounds in the hint so the operator
 * knows why a too-large value gets rejected.
 */
export default function HistorySettingsPanel() {
  const [loaded, setLoaded]     = useState(false);
  const [loadError, setLoadErr] = useState(null);
  const [saving, setSaving]     = useState(false);
  const [toast, setToast]       = useState(null);

  const [retentionHours, setRetentionHours] = useState(48);
  const [minHours, setMinHours] = useState(1);
  const [maxHours, setMaxHours] = useState(720);
  const [updatedAt, setUpdatedAt] = useState(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const dto = await fetchHistorySettings();
        if (cancelled) return;
        setRetentionHours(dto.retentionHours ?? 48);
        setMinHours(dto.minHours ?? 1);
        setMaxHours(dto.maxHours ?? 720);
        setUpdatedAt(dto.updatedAt || null);
        setLoaded(true);
      } catch (err) {
        if (!cancelled) setLoadErr(err.message || String(err));
      }
    })();
    return () => { cancelled = true; };
  }, []);

  const onSave = async () => {
    setSaving(true);
    setToast(null);
    try {
      const n = Number(retentionHours);
      if (!Number.isFinite(n) || n < minHours || n > maxHours) {
        throw new Error(`보관 주기는 ${minHours}~${maxHours}시간 사이여야 합니다.`);
      }
      const saved = await updateHistorySettings({ retentionHours: n });
      setRetentionHours(saved.retentionHours);
      setUpdatedAt(saved.updatedAt || null);
      setToast({ kind: 'ok', text: `보관 주기를 ${saved.retentionHours}시간으로 저장했습니다.` });
    } catch (err) {
      setToast({ kind: 'err', text: `저장 실패: ${err.message || err}` });
    } finally {
      setSaving(false);
    }
  };

  if (loadError) {
    return <div className="settings__empty settings__empty--error">Failed to load settings: {loadError}</div>;
  }
  if (!loaded) {
    return <div className="settings__empty">Loading Time Machine settings…</div>;
  }

  const days = (retentionHours / 24).toFixed(1);

  return (
    <>
      <div className="settings__toolbar">
        <div>
          <h2 className="settings__title">Time Machine</h2>
          <div className="settings__sub">
            과거 메트릭 스냅샷 보관 주기를 설정합니다. 저장 즉시 다음 purge 주기부터 적용됩니다 — 재시작 불필요.
          </div>
        </div>
      </div>

      {toast && (
        <div className={`settings__msg settings__msg--${toast.kind}`}>
          {toast.text}
          <button
            type="button"
            className="settings__msg-close"
            onClick={() => setToast(null)}
            aria-label="Dismiss"
          >×</button>
        </div>
      )}

      <section className="notif-card">
        <header className="notif-card__header">
          <div className="notif-card__title">
            <span className="notif-card__logo">⏱</span>
            <div>
              <div className="notif-card__name">기록 보관 주기</div>
              <div className="notif-card__hint">
                허용 범위: {minHours}~{maxHours}시간 ({(maxHours / 24).toFixed(0)}일).
                값을 줄이면 다음 purge 주기에 초과분이 삭제됩니다.
              </div>
            </div>
          </div>
        </header>

        <div className="notif-grid">
          <label className="notif-field">
            <span className="notif-field__label">Retention (hours)</span>
            <input
              type="number"
              className="notif-field__input"
              min={minHours}
              max={maxHours}
              step={1}
              value={retentionHours}
              onChange={(e) => setRetentionHours(e.target.value)}
            />
            <div className="notif-field__hint">≈ {days}일</div>
          </label>
        </div>

        <footer className="notif-card__footer">
          <div className="notif-card__meta">
            {updatedAt ? `Last saved ${new Date(updatedAt).toLocaleString()}` : 'Never saved (using yaml default)'}
          </div>
          <div className="notif-card__actions">
            <button
              type="button"
              className="tgt-btn tgt-btn--primary"
              onClick={onSave}
              disabled={saving}
            >{saving ? 'Saving…' : 'Save Changes'}</button>
          </div>
        </footer>
      </section>
    </>
  );
}
