import { AppError } from './errors';
// AuthSession does not expose an AbortSignal for discovery/token operations.
export async function withTimeout<T>(operation: Promise<T>, ms = 15000): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([
      operation,
      new Promise<never>((_, reject) => {
        timer = setTimeout(() => reject(new AppError('networkError')), ms);
      }),
    ]);
  } finally {
    if (timer) clearTimeout(timer);
  }
}
