import type { MessageKey } from '../locales';
export class AppError extends Error {
  constructor(
    public readonly key: MessageKey,
    public readonly expired = false,
  ) {
    super(key);
  }
}
export function errorKey(error: unknown, fallback: MessageKey): MessageKey {
  return error instanceof AppError ? error.key : fallback;
}
// Thrown by the real implementation of a capability whose backend/provider does not exist yet.
// Screens render it through the normal error path as a clear "not available yet" state.
export class FeatureUnavailableError extends AppError {
  constructor(public readonly feature: string) {
    super('featureUnavailable');
  }
}
// The user closed the identity provider's page without signing in. Not an error to show:
// the app silently stays on (returns to) the welcome screen.
export class UserCancelledError extends AppError {
  constructor() {
    super('authError');
    this.name = 'UserCancelledError';
  }
}
