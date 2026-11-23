import React, { useEffect, useRef, useState } from 'react';
import { Share, StyleSheet, Text, View } from 'react-native';
import * as Clipboard from 'expo-clipboard';
import { Button, ErrorNotice, Icon, Sheet, c } from './ui';
import { DetailRow } from './money';
import { theme } from '../theme';
import { t, tf } from '../locales';
import { useSession } from '../session/SessionContext';
import { shortId } from '../utils/amountParts';

// The customer's receiving details from GET /api/profile (contract §11): their email (the key other
// customers look up, §12) and their NEOS user ID. Opened from Home ("Details") and Profile. Bank
// details (IBAN or local account for top-ups) do not exist yet: the reserved block says so.
export function AccountDetailsSheet({
  visible,
  onClose,
}: {
  visible: boolean;
  onClose: () => void;
}) {
  const { profile } = useSession();
  const email = profile.customer?.email ?? profile.identity.email;
  const userId = profile.customer?.userId ?? null;
  const [copied, setCopied] = useState<'email' | 'id' | null>(null);
  const [shareError, setShareError] = useState(false);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  async function copy(which: 'email' | 'id', value: string) {
    await Clipboard.setStringAsync(value);
    setCopied(which);
    setTimeout(() => mounted.current && setCopied(null), 2000);
  }
  async function share() {
    setShareError(false);
    try {
      await Share.share({ message: tf('shareMessage', { email }) });
    } catch {
      if (mounted.current) setShareError(true);
    }
  }
  return (
    <Sheet visible={visible} title={t('accountDetails')} onClose={onClose}>
      <Text style={styles.intro}>{t('receiveBody')}</Text>
      <View>
        <DetailRow
          label={t('yourEmail')}
          value={email}
          copyLabel={t('copyEmail')}
          copied={copied === 'email'}
          onCopy={() => void copy('email', email)}
        />
        {userId && (
          <DetailRow
            label={t('yourUserId')}
            value={shortId(userId)}
            mono
            copyLabel={t('copyUserId')}
            copied={copied === 'id'}
            onCopy={() => void copy('id', userId)}
          />
        )}
      </View>
      <ErrorNotice message={shareError ? t('unexpectedError') : null} />
      <Button secondary label={t('shareDetails')} onPress={() => void share()} />
      {/* Reserved for bank details once top-up exists (no data, no placeholder numbers). */}
      <View
        style={styles.reserved}
        accessible
        accessibilityLabel={`${t('bankDetails')}. ${t('bankDetailsPending')}`}
      >
        <Icon name="briefcase" color={c.muted} size={20} />
        <View style={styles.reservedText}>
          <Text style={styles.reservedTitle}>{t('bankDetails')}</Text>
          <Text style={styles.reservedBody}>{t('bankDetailsPending')}</Text>
        </View>
      </View>
    </Sheet>
  );
}

const type = theme.text;
const styles = StyleSheet.create({
  intro: { ...type.caption, color: c.muted },
  reserved: {
    flexDirection: 'row',
    gap: theme.space.md,
    padding: theme.space.lg,
    borderRadius: theme.radius.md,
    borderWidth: 1,
    borderStyle: 'dashed',
    borderColor: c.border,
  },
  reservedText: { flex: 1, gap: 2 },
  reservedTitle: { ...type.bodyStrong, color: c.ink },
  reservedBody: { ...type.caption, color: c.muted },
});
