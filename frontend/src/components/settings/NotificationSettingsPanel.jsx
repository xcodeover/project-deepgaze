import { useCallback, useEffect, useState } from 'react';
import {
  fetchNotificationSettings,
  updateNotificationSettings,
  sendTestNotification,
} from '../../api/notifications.js';

/**
 * Notifications tab — currently just Telegram, but laid out so adding Slack /
 * email / PagerDuty later slots into the same card grid.
 *
 * Token UX: the backend decrypts and returns the plaintext on GET (endpoint
 * is JWT-gated; disk stays AES-GCM encrypted), so the field is populated
 * with whatever is on file. We still track {@link botTokenDirty} so the PUT
 * payload only includes the field when it actually changed — avoiding a
 * round-trip re-encrypt for a no-op save.
 */

export default function NotificationSettingsPanel() {
  const [loaded, setLoaded] = useState(false);
  const [loadError, setLoadError] = useState(null);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [toast, setToast] = useState(null); // {kind:'ok'|'err', text}

  const [enabled, setEnabled] = useState(false);
  const [notifyOnCritical, setNotifyOnCritical] = useState(true);
  const [notifyOnWarning, setNotifyOnWarning]   = useState(true);
  const [chatId, setChatId] = useState('');

  // Token is a controlled input with a "dirty" flag. When the stored token is
  // set and the user hasn't retyped, we show PLACEHOLDER and skip the field
  // on PUT.
  const [botToken, setBotToken] = useState('');
  const [botTokenDirty, setBotTokenDirty] = useState(false);
  const [botTokenVisible, setBotTokenVisible] = useState(false);
  const [tokenOnFile, setTokenOnFile] = useState(false);
  const [tokenPreview, setTokenPreview] = useState(null);
  const [updatedAt, setUpdatedAt] = useState(null);

  /* --- Load ---------------------------------------------------------- */

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const dto = await fetchNotificationSettings();
        if (cancelled) return;
        setEnabled(!!dto.enabled);
        setNotifyOnCritical(dto.notifyOnCritical !== false);
        setNotifyOnWarning (dto.notifyOnWarning  !== false);
        setChatId(dto.telegramChatId || '');
        setTokenOnFile(!!dto.telegramBotTokenSet);
        setTokenPreview(dto.telegramBotTokenPreview || null);
        setBotToken(dto.telegramBotToken || '');
        setBotTokenDirty(false);
        setUpdatedAt(dto.updatedAt || null);
        setLoaded(true);
      } catch (err) {
        if (!cancelled) setLoadError(err.message || String(err));
      }
    })();
    return () => { cancelled = true; };
  }, []);

  /* --- Handlers ------------------------------------------------------ */

  const onTokenChange = useCallback((e) => {
    setBotToken(e.target.value);
    setBotTokenDirty(true);
  }, []);

  const buildDto = useCallback(() => {
    const dto = {
      enabled,
      notifyOnCritical,
      notifyOnWarning,
      telegramChatId: chatId.trim(),
    };
    // Only include the token when the user typed something new. Empty string
    // after retyping = "clear the stored token".
    if (botTokenDirty) dto.telegramBotToken = botToken;
    return dto;
  }, [enabled, notifyOnCritical, notifyOnWarning, chatId, botTokenDirty, botToken]);

  const onSave = useCallback(async () => {
    setSaving(true);
    setToast(null);
    try {
      const dto = buildDto();
      const saved = await updateNotificationSettings(dto);
      setTokenOnFile(!!saved.telegramBotTokenSet);
      setTokenPreview(saved.telegramBotTokenPreview || null);
      setBotToken(saved.telegramBotToken || '');
      setBotTokenDirty(false);
      setBotTokenVisible(false);
      setUpdatedAt(saved.updatedAt || null);
      setToast({ kind: 'ok', text: 'Settings saved.' });
    } catch (err) {
      setToast({ kind: 'err', text: `Save failed: ${err.message || err}` });
    } finally {
      setSaving(false);
    }
  }, [buildDto]);

  const onTest = useCallback(async () => {
    setTesting(true);
    setToast(null);
    try {
      const payload = {
        telegramChatId: chatId.trim(),
      };
      // Send the typed token if the user is testing against unsaved creds,
      // otherwise let the backend fall back to what's stored.
      if (botTokenDirty && botToken.trim().length > 0) {
        payload.telegramBotToken = botToken;
      }
      const result = await sendTestNotification(payload);
      setToast({
        kind: result.ok ? 'ok' : 'err',
        text: result.message || (result.ok ? 'Delivered.' : 'Test failed.'),
      });
    } catch (err) {
      setToast({ kind: 'err', text: `Test failed: ${err.message || err}` });
    } finally {
      setTesting(false);
    }
  }, [chatId, botTokenDirty, botToken]);

  /* --- Render -------------------------------------------------------- */

  if (loadError) {
    return <div className="settings__empty settings__empty--error">Failed to load settings: {loadError}</div>;
  }
  if (!loaded) {
    return <div className="settings__empty">Loading notification settings…</div>;
  }

  const canTest = (botTokenDirty ? botToken.trim().length > 0 : tokenOnFile)
               && chatId.trim().length > 0;

  return (
    <>
      <div className="settings__toolbar">
        <div>
          <h2 className="settings__title">Notification Channels</h2>
          <div className="settings__sub">
            Route firing alerts to an external channel. Changes hot-reload — the next alert honours the new config with no restart.
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
            <span className="notif-card__logo notif-card__logo--telegram">T</span>
            <div>
              <div className="notif-card__name">Telegram</div>
              <div className="notif-card__hint">
                Create a bot via <a href="https://t.me/BotFather" target="_blank" rel="noreferrer">@BotFather</a>,
                paste its token, then get your chat id from <a href="https://t.me/userinfobot" target="_blank" rel="noreferrer">@userinfobot</a>.
              </div>
            </div>
          </div>
          <label className="toggle">
            <input
              type="checkbox"
              checked={enabled}
              onChange={(e) => setEnabled(e.target.checked)}
            />
            <span className="toggle__slider" />
            <span className="toggle__label">{enabled ? 'Enabled' : 'Disabled'}</span>
          </label>
        </header>

        <div className="notif-grid">
          <label className="notif-field">
            <span className="notif-field__label">Bot Token</span>
            <div className="notif-field__row">
              <input
                type={botTokenVisible ? 'text' : 'password'}
                className="notif-field__input"
                value={botToken}
                onChange={onTokenChange}
                placeholder="123456789:ABCdefGhIJKlmNopQrStUvWxYz"
                autoComplete="off"
                spellCheck={false}
              />
              <button
                type="button"
                className="tgt-btn tgt-btn--ghost notif-field__reveal"
                onClick={() => setBotTokenVisible((v) => !v)}
              >{botTokenVisible ? 'Hide' : 'Show'}</button>
            </div>
            {tokenOnFile && !botTokenDirty && (
              <div className="notif-field__hint">
                저장된 토큰 — 필요하면 그대로 수정하세요. 끝 4자리: <code>{tokenPreview || '…'}</code>
              </div>
            )}
          </label>

          <label className="notif-field">
            <span className="notif-field__label">Chat ID</span>
            <input
              type="text"
              className="notif-field__input"
              value={chatId}
              onChange={(e) => setChatId(e.target.value)}
              placeholder="e.g. -1001234567890 or 987654321"
              autoComplete="off"
              spellCheck={false}
            />
          </label>
        </div>

        <fieldset className="notif-fieldset">
          <legend>Severity filter</legend>
          <label className="check">
            <input
              type="checkbox"
              checked={notifyOnCritical}
              onChange={(e) => setNotifyOnCritical(e.target.checked)}
            />
            <span>Notify on <b className="sev sev--crit">CRITICAL</b> alerts</span>
          </label>
          <label className="check">
            <input
              type="checkbox"
              checked={notifyOnWarning}
              onChange={(e) => setNotifyOnWarning(e.target.checked)}
            />
            <span>Notify on <b className="sev sev--warn">WARNING</b> alerts</span>
          </label>
        </fieldset>

        <footer className="notif-card__footer">
          <div className="notif-card__meta">
            {updatedAt ? `Last saved ${new Date(updatedAt).toLocaleString()}` : 'Never saved'}
          </div>
          <div className="notif-card__actions">
            <button
              type="button"
              className="tgt-btn tgt-btn--ghost"
              onClick={onTest}
              disabled={!canTest || testing || saving}
              title={canTest ? 'Send a test Markdown message to the configured chat.'
                             : 'Set both Bot Token and Chat ID first.'}
            >{testing ? 'Sending…' : 'Send Test Message'}</button>
            <button
              type="button"
              className="tgt-btn tgt-btn--primary"
              onClick={onSave}
              disabled={saving || testing}
            >{saving ? 'Saving…' : 'Save Changes'}</button>
          </div>
        </footer>
      </section>
    </>
  );
}
