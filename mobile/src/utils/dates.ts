// Local date and time of an ISO-8601 instant, e.g. "2 Nov 2026, 14:03". Falls back to the raw
// value when it cannot be parsed (never invents a date).
export function formatDateTime(iso: string, locale: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  try {
    return new Intl.DateTimeFormat(locale, {
      day: 'numeric',
      month: 'short',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    }).format(date);
  } catch {
    return date.toISOString();
  }
}

// Unambiguous UTC rendering of an ISO-8601 instant, e.g. "2026-10-30 14:03:11 UTC". Locale
// independent (receipts show it next to the local time). Raw value when it cannot be parsed.
export function formatDateTimeUtc(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return `${date.toISOString().slice(0, 19).replace('T', ' ')} UTC`;
}

// Local time of an ISO-8601 instant, e.g. "14:03" / "2:03 PM". Raw value when unparseable.
export function formatTime(iso: string, locale: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  try {
    return new Intl.DateTimeFormat(locale, { hour: '2-digit', minute: '2-digit' }).format(date);
  } catch {
    return date.toISOString().slice(11, 16);
  }
}
