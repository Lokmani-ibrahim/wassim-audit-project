// Pure view-model for the dashboard wallets (GET /api/wallets, contract §10). It only reshapes
// what the backend sent: no sums, no totals across currencies (that would need FX), no
// derived balances. A wallet that is not provisioned is NEVER represented as an amount.
import type { Currency, WalletsResponse } from '../../api/contract';
import { formatMoney, isZeroMinor } from '../../money';
import { t } from '../../locales';

export type AvailableView =
  { kind: 'amount'; minor: string; scale: number } | { kind: 'settingUp' };

export interface WalletItem {
  currency: Currency;
  scale: number;
  available: AvailableView;
  // Only when provisioned and non-zero.
  pendingMinor: string | null;
}

export type WalletsBanner = 'provisioning' | 'notEligible' | null;

export interface WalletsView {
  banner: WalletsBanner;
  asOf: string;
  items: WalletItem[];
}

export function walletsViewModel(response: WalletsResponse): WalletsView {
  return {
    banner:
      response.walletsStatus === 'PROVISIONING'
        ? 'provisioning'
        : response.walletsStatus === 'NOT_ELIGIBLE'
          ? 'notEligible'
          : null,
    asOf: response.asOf,
    items: response.wallets.map((wallet) => {
      const provisioned = wallet.status !== 'NOT_PROVISIONED' && wallet.availableMinor !== null;
      return {
        currency: wallet.currency,
        scale: wallet.scale,
        available: provisioned
          ? { kind: 'amount', minor: wallet.availableMinor!, scale: wallet.scale }
          : { kind: 'settingUp' },
        pendingMinor:
          provisioned && wallet.pendingMinor !== null && !isZeroMinor(wallet.pendingMinor)
            ? wallet.pendingMinor
            : null,
      };
    }),
  };
}

export function availableText(item: WalletItem, locale: string): string {
  return item.available.kind === 'amount'
    ? formatMoney(item.available.minor, item.currency, locale, { scale: item.available.scale })
    : t('settingUp');
}

export function pendingText(item: WalletItem, locale: string): string | null {
  return item.pendingMinor === null
    ? null
    : `${formatMoney(item.pendingMinor, item.currency, locale, { scale: item.scale })} ${t('pending')}`;
}
