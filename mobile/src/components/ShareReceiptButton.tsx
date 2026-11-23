import React, { useEffect, useRef, useState } from 'react';
import { View } from 'react-native';
import { Button, ErrorNotice } from './ui';
import { config, isSandbox } from '../config';
import { locale, t, type MessageKey } from '../locales';
import { useSession } from '../session/SessionContext';
import type { ReceiptData } from '../receipts/model';
import { buildReceiptHtml } from '../receipts/html';
import { shareReceipt } from '../receipts/share';

// "Share receipt": builds the PDF on the device and opens the share sheet. Only rendered for
// confirmed transactions (history items, or a succeeded transfer via receiptFromOutcome).
export function ShareReceiptButton({ data }: { data: ReceiptData }) {
  const { profile } = useSession();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<MessageKey | null>(null);
  const running = useRef(false);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  async function share() {
    if (running.current) return;
    running.current = true;
    setBusy(true);
    setError(null);
    try {
      const html = buildReceiptHtml(data, {
        locale,
        sandbox: isSandbox(config),
        // Own email only: the ID token's given_name is self-declared and unverified.
        accountEmail: profile.customer?.email ?? profile.identity.email ?? null,
        generatedAt: new Date().toISOString(),
      });
      const result = await shareReceipt(html, data.transactionId, t('receiptShareTitle'));
      if (result === 'unavailable' && mounted.current) setError('receiptSharingUnavailable');
    } catch {
      if (mounted.current) setError('receiptFailed');
    } finally {
      running.current = false;
      if (mounted.current) setBusy(false);
    }
  }

  return (
    <View style={{ gap: 8 }}>
      <ErrorNotice message={error && t(error)} />
      <Button secondary label={t('shareReceipt')} loading={busy} onPress={() => void share()} />
    </View>
  );
}
