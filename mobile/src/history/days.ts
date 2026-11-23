// Groups history items by local calendar day for display. Presentation only: no sorting, no
// amounts, no balances (D4). Items keep the order the server returned; a day's group appears where
// its first item appears, so a day split across pages is merged, never duplicated.

export interface DayGroup<T> {
  // Local "YYYY-MM-DD", or the raw value when the timestamp cannot be parsed.
  key: string;
  // When the day is today or yesterday (local time); null otherwise.
  relative: 'today' | 'yesterday' | null;
  // First instant of the group, for formatting the full date.
  date: Date | null;
  items: T[];
}

function dayKey(date: Date): string {
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}

export function groupByDay<T>(items: T[], timeOf: (item: T) => string, now: Date): DayGroup<T>[] {
  const today = dayKey(now);
  const before = new Date(now);
  before.setDate(now.getDate() - 1);
  const yesterday = dayKey(before);
  const groups = new Map<string, DayGroup<T>>();
  for (const item of items) {
    const raw = timeOf(item);
    const date = new Date(raw);
    const valid = !Number.isNaN(date.getTime());
    const key = valid ? dayKey(date) : raw;
    let group = groups.get(key);
    if (!group) {
      group = {
        key,
        relative: !valid ? null : key === today ? 'today' : key === yesterday ? 'yesterday' : null,
        date: valid ? date : null,
        items: [],
      };
      groups.set(key, group);
    }
    group.items.push(item);
  }
  return [...groups.values()];
}

// "Wednesday 4 November" (this year) or "Wednesday 4 November 2025"; the raw key if unparseable.
export function formatDay(
  group: Pick<DayGroup<unknown>, 'date' | 'key'>,
  locale: string,
  now: Date,
) {
  if (!group.date) return group.key;
  try {
    return new Intl.DateTimeFormat(locale, {
      weekday: 'long',
      day: 'numeric',
      month: 'long',
      ...(group.date.getFullYear() === now.getFullYear() ? {} : { year: 'numeric' }),
    }).format(group.date);
  } catch {
    return group.key;
  }
}
