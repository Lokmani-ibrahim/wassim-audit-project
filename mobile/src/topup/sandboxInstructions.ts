import type { Currency } from '../api/contract';

// Add money, sandbox preview: EXAMPLE deposit instructions only. There is no top-up backend yet
// (the sponsor bank and the backend developer's Top-Up simulation, INTEGRATION_STATUS), so:
// - nothing here is fetched, nothing is credited, no balance or transaction is created;
// - the account number is deliberately not an account of any bank: the "XX" country code does
//   not exist and the check digits are "00", so it can never pass an IBAN check;
// - only the reference is real data: it is derived from the user's NEOS user ID, the key the
//   future incoming-transfer matching is expected to use.
// Shown only in sandbox builds (topUpPreviewAvailableFor); production keeps "Not available yet".
// Not a mock (no mock marker, not under src/mocks): it has to work in sandbox release builds, and it fakes
// no backend response. Listed in src/mocks/registry.ts as a sandbox-only action.

export const SANDBOX_BANK_NAME = 'Sponsor bank (sandbox)';
export const SANDBOX_ACCOUNT_HOLDER = 'NEOS Client Funds (sandbox)';
export const SANDBOX_ACCOUNT_NUMBER = 'XX00 SAND BOX0 0000 0000 0000 00';

export interface DepositInstructions {
  bankName: string;
  accountHolder: string;
  accountNumber: string;
  currency: Currency;
  reference: string;
}

// "NEOS-" + the first 8 characters of the NEOS user ID, upper case. Null without a usable ID.
export function depositReference(userId: string | null | undefined): string | null {
  const id = (userId ?? '').trim();
  if (id.length < 8) return null;
  return `NEOS-${id.slice(0, 8).toUpperCase()}`;
}

export function sandboxDepositInstructions(
  userId: string | null | undefined,
  currency: Currency,
): DepositInstructions | null {
  const reference = depositReference(userId);
  if (!reference) return null;
  return {
    bankName: SANDBOX_BANK_NAME,
    accountHolder: SANDBOX_ACCOUNT_HOLDER,
    accountNumber: SANDBOX_ACCOUNT_NUMBER,
    currency,
    reference,
  };
}

// Same signal as Convert (src/fx fxAvailable): a sandbox build with a real session.
export function topUpPreviewAvailableFor(cfg: { sandbox: boolean; authMocked: boolean }): boolean {
  return cfg.sandbox && !cfg.authMocked;
}
