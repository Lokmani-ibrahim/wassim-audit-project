import { CURRENCIES, type WalletsResponse } from '../api/contract';
import { CURRENCY_SCALE } from '../money';
import type { Services } from '../types/domain';

export const WALLETS_MARKER = '__NEOS_MOCK__:wallets';
// Exact GET /api/wallets shape (contract §10): READY, all 6 currencies in declaration order,
// zero balances as integer-minor-unit strings. No fake money.
export function createMockWallets(): Services['wallets'] {
  return {
    async list(): Promise<WalletsResponse> {
      return {
        walletsStatus: 'READY',
        asOf: new Date().toISOString(),
        wallets: CURRENCIES.map((currency) => ({
          currency,
          scale: CURRENCY_SCALE[currency],
          asset: `${currency}/${CURRENCY_SCALE[currency]}`,
          status: 'ACTIVE',
          availableMinor: '0',
          pendingMinor: '0',
        })),
      };
    },
  };
}
