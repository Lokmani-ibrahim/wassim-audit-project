import type { DemoAuthInput } from '../types/domain';
import type { MessageKey } from '../locales';

export const normalizeEmail = (email: string) => email.trim().toLowerCase();
export function authErrors(
  input: DemoAuthInput,
): Partial<Record<'email' | 'password' | 'confirmPassword', MessageKey>> {
  const errors: ReturnType<typeof authErrors> = {};
  const email = normalizeEmail(input.email);
  if (email.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) errors.email = 'emailError';
  if (!input.password.trim()) errors.password = 'passwordError';
  if (input.intent === 'create' && input.password !== input.confirmPassword)
    errors.confirmPassword = 'passwordMismatch';
  return errors;
}
