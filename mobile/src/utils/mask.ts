// Same masking rule as the backend's recipient lookup (first character of the local part,
// full domain): "jane.doe@example.com" -> "j***@example.com".
export function maskEmail(email: string): string {
  const at = email.lastIndexOf('@');
  if (at <= 0) return '***';
  return `${email.slice(0, 1)}***${email.slice(at)}`;
}
