// Display-only reading of the OIDC ID token (Keycloak). The token is NOT verified here and its
// claims are never used for any trust or authorization decision: the backend authorizes every
// call with the access token. The only use is a friendly greeting (`given_name`).
//
// Pure (no Hermes atob/TextDecoder dependency) so it runs the same on device and under node.

const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
const LOOKUP = new Map<string, number>([...ALPHABET].map((char, index) => [char, index]));

function base64UrlToBytes(input: string): number[] {
  const text = input.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  if (text.length % 4 === 1) throw new Error('invalid base64url length');
  const bytes: number[] = [];
  let buffer = 0;
  let bits = 0;
  for (const char of text) {
    const value = LOOKUP.get(char);
    if (value === undefined) throw new Error('invalid base64url character');
    buffer = (buffer << 6) | value;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      bytes.push((buffer >> bits) & 0xff);
    }
  }
  return bytes;
}

function utf8ToString(bytes: number[]): string {
  let out = '';
  for (let index = 0; index < bytes.length;) {
    const first = bytes[index]!;
    let codePoint: number;
    let extra: number;
    if (first < 0x80) [codePoint, extra] = [first, 0];
    else if (first >= 0xc2 && first < 0xe0) [codePoint, extra] = [first & 0x1f, 1];
    else if (first >= 0xe0 && first < 0xf0) [codePoint, extra] = [first & 0x0f, 2];
    else if (first >= 0xf0 && first < 0xf5) [codePoint, extra] = [first & 0x07, 3];
    else throw new Error('invalid UTF-8');
    if (index + extra >= bytes.length) throw new Error('truncated UTF-8');
    for (let k = 1; k <= extra; k += 1) {
      const next = bytes[index + k]!;
      if ((next & 0xc0) !== 0x80) throw new Error('invalid UTF-8 continuation');
      codePoint = (codePoint << 6) | (next & 0x3f);
    }
    if (codePoint > 0x10ffff || (codePoint >= 0xd800 && codePoint <= 0xdfff))
      throw new Error('invalid code point');
    out += String.fromCodePoint(codePoint);
    index += extra + 1;
  }
  return out;
}

// Payload claims of a compact JWS, or null if it is not one.
export function decodeJwtClaims(token: string | null | undefined): Record<string, unknown> | null {
  if (!token) return null;
  const parts = token.split('.');
  if (parts.length !== 3 || !parts[1]) return null;
  try {
    const claims: unknown = JSON.parse(utf8ToString(base64UrlToBytes(parts[1])));
    return typeof claims === 'object' && claims !== null && !Array.isArray(claims)
      ? (claims as Record<string, unknown>)
      : null;
  } catch {
    return null;
  }
}

export function givenNameFromIdToken(idToken: string | null | undefined): string | null {
  const value = decodeJwtClaims(idToken)?.given_name;
  if (typeof value !== 'string') return null;
  const name = value.trim();
  return name === '' ? null : name.slice(0, 64);
}

// Greeting name: the first name if known, else the email local part. Never the full email.
// Returns '' when neither is usable (the caller then shows the brand).
export function displayName(
  givenName: string | null | undefined,
  email: string | null | undefined,
): string {
  const name = givenName?.trim();
  if (name) return name;
  const at = email?.indexOf('@') ?? -1;
  const local = (at > 0 ? email!.slice(0, at) : at === -1 ? (email ?? '') : '').trim();
  return local;
}
