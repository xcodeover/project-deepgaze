import { useState } from 'react';

/**
 * Password field with a show/hide toggle.
 *
 * Spreads arbitrary `<input>` props so each call site keeps its own `className`,
 * `placeholder`, `autoComplete`, `onChange`, etc. — the wrapper only adds the
 * eye toggle and flips `type` between "password" and "text". We set
 * `autoComplete="new-password"` as a safe default so browsers don't autofill
 * the wrong credential into target-password / reset-password forms.
 *
 * The toggle button is `tabIndex={-1}` on purpose: keyboard users moving
 * through the form with Tab should land on the next field, not on the
 * visibility button.
 */
export default function PasswordInput({
  className = '',
  disabled = false,
  autoComplete = 'new-password',
  wrapClassName = '',
  ...rest
}) {
  const [show, setShow] = useState(false);
  return (
    <span className={`pw-input${wrapClassName ? ` ${wrapClassName}` : ''}`}>
      <input
        {...rest}
        disabled={disabled}
        autoComplete={autoComplete}
        type={show ? 'text' : 'password'}
        className={`${className} pw-input__field`.trim()}
      />
      <button
        type="button"
        className="pw-input__eye"
        onClick={() => setShow((v) => !v)}
        disabled={disabled}
        aria-label={show ? 'Hide password' : 'Show password'}
        aria-pressed={show}
        tabIndex={-1}
      >
        {show ? <EyeOffIcon /> : <EyeIcon />}
      </button>
    </span>
  );
}

function EyeIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
         strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8S1 12 1 12z" />
      <circle cx="12" cy="12" r="3" />
    </svg>
  );
}

function EyeOffIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
         strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M17.94 17.94A10.94 10.94 0 0 1 12 20c-7 0-11-8-11-8a21.78 21.78 0 0 1 5.06-6.06" />
      <path d="M9.9 4.24A10.94 10.94 0 0 1 12 4c7 0 11 8 11 8a21.83 21.83 0 0 1-3.17 4.19" />
      <path d="M14.12 14.12a3 3 0 1 1-4.24-4.24" />
      <line x1="1" y1="1" x2="23" y2="23" />
    </svg>
  );
}
