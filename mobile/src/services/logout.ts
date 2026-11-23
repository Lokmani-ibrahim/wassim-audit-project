// OIDC RP-initiated logout uses the discovery end-session URL in the same browser
// session as login. The caller clears local credentials before opening it.
export async function endBrowserSession(
  endpoint: string | undefined,
  clientId: string,
  redirectUri: string,
  open: (url: string, redirectUri: string) => Promise<{ type: string }>,
): Promise<boolean> {
  if (!endpoint) return false;
  const parameters = [
    `client_id=${encodeURIComponent(clientId)}`,
    `post_logout_redirect_uri=${encodeURIComponent(redirectUri)}`,
  ].join('&');
  const url = `${endpoint}${endpoint.includes('?') ? '&' : '?'}${parameters}`;
  try {
    return (await open(url, redirectUri)).type === 'success';
  } catch {
    return false;
  }
}
