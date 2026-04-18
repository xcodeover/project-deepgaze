/**
 * JDBC drivers return column labels in different cases:
 *   - Oracle  → UPPERCASE          (e.g. "STATUS", "CNT")
 *   - MySQL   → as queried         (lowercase or original)
 *   - MSSQL   → as queried
 *   - SHOW GLOBAL STATUS / VARIABLES → "Variable_name", "Value" verbatim
 *
 * pick(row, 'status', 'state') tries each key in original / upper / lower
 * variants and returns the first non-null match. This keeps the SQL clean
 * (no driver-specific quoting) and the chart components engine-agnostic.
 */
export function pick(row, ...keys) {
  for (const key of keys) {
    if (row[key] != null) return row[key];
    const upper = key.toUpperCase();
    if (row[upper] != null) return row[upper];
    const lower = key.toLowerCase();
    if (row[lower] != null) return row[lower];
  }
  return null;
}
