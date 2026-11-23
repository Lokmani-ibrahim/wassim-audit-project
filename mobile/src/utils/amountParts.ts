// Display-only split of an already formatted amount into its main part and its minor units, so
// the minor units can be drawn smaller ("€72" + ".90"). It never formats, rounds or parses money:
// the text comes from src/money unchanged, and joining the parts gives back the same string.
export function splitAmount(text: string, locale: string): { main: string; minor: string } {
  let decimal = '.';
  try {
    decimal =
      new Intl.NumberFormat(locale).formatToParts(1.5).find((part) => part.type === 'decimal')
        ?.value ?? '.';
  } catch {
    // Keep '.'.
  }
  const at = text.lastIndexOf(decimal);
  // Only a separator directly followed by digits marks minor units ("Setting up…" has none).
  if (at <= 0 || !/^\d/.test(text.slice(at + 1))) return { main: text, minor: '' };
  return { main: text.slice(0, at), minor: text.slice(at) };
}

// Up to two initials for an avatar ("Amira Ben Salah" → "AB"); '' when there is no letter.
export function initials(name: string): string {
  const words = name
    .trim()
    .split(/[\s._-]+/)
    .filter((word) => /\p{L}/u.test(word));
  return words
    .slice(0, 2)
    .map((word) => [...word].find((char) => /\p{L}/u.test(char)) ?? '')
    .join('')
    .toUpperCase();
}

// "7bf8f8ad…3690de0"-style shortening for long identifiers (first 8, last 6). Copy uses the full one.
export function shortId(id: string): string {
  return id.length > 16 ? `${id.slice(0, 8)}…${id.slice(-6)}` : id;
}
