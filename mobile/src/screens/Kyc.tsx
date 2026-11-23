import React from 'react';
import { StyleSheet, View } from 'react-native';
import {
  Badge,
  Button,
  Copy,
  ErrorNotice,
  Icon,
  InfoRow,
  Intro,
  Screen,
  TextButton,
  c,
} from '../components/ui';
import { t } from '../locales';
import type { Journey } from '../hooks/useJourney';
import { useStatusPolling } from '../hooks/useStatusPolling';
import { POLLING_ROUTES } from '../navigation/state';

// KYC intro. "Start verification" starts KYC (POST /api/kyc/start), gets a Sumsub SDK token and
// opens the Sumsub SDK (services/sumsub.ts). The camera, document and selfie/liveness steps are
// the SDK's own screens.
export function Kyc({ journey: j }: { journey: Journey }) {
  return (
    <Screen
      progress={0.45}
      footer={
        <>
          <ErrorNotice message={j.error && t(j.error)} />
          <Button label={t('startVerification')} onPress={j.verify} loading={!!j.busy} />
        </>
      }
    >
      <Intro title={t('kycTitle')} body={t('kycBody')} />
      <InfoRow icon="file-text" title={t('governmentId')} description={t('governmentIdSub')} />
      <InfoRow icon="user" title={t('selfie')} description={t('selfieSub')} />
      <InfoRow icon="clock" title={t('minutes')} description={t('minutesSub')} />
      <Copy muted style={styles.note}>
        {t('kycPartnerNote')}
      </Copy>
    </Screen>
  );
}

export function StatusScreen({ journey: j, onDev }: { journey: Journey; onDev: () => void }) {
  const approved = j.route === 'approved';
  const ready = j.route === 'ready';
  const rejected = j.route === 'rejected';
  const more = j.route === 'moreInfo';
  const pending = j.route === 'pending';
  const restricted = j.route === 'restricted';
  const success = approved || ready;
  // SUSPENDED + RESUBMISSION_REQUIRED: the suspension reason is not exposed, so the wording is
  // neutral (product decision). A refused SDK session switches to the restricted screen.
  const suspendedResubmission = more && j.profile?.account === 'SUSPENDED';
  // Polls GET /api/profile while waiting for the Sumsub decision (kyc/polling.ts).
  useStatusPolling(POLLING_ROUTES.includes(j.route), j.poll, j.pollKey);
  const title = t(
    approved
      ? 'approvedTitle'
      : ready
        ? 'readyTitle'
        : rejected
          ? 'rejectedTitle'
          : more
            ? 'moreTitle'
            : restricted
              ? 'restrictedTitle'
              : 'pendingTitle',
  );
  const body = t(
    approved
      ? 'approvedBody'
      : ready
        ? 'readyBody'
        : rejected
          ? 'rejectedBody'
          : suspendedResubmission
            ? 'moreBodySuspended'
            : more
              ? 'moreBody'
              : restricted
                ? 'restrictedBody'
                : j.reviewLocked
                  ? 'pendingBodyReview'
                  : 'pendingBody',
  );
  // Waiting screens: relaunch the SDK (unless a manual review is needed) and check the status.
  const waiting = (pending || more) && !j.reviewLocked;
  const action = approved
    ? () => j.go('ready')
    : ready
      ? j.home
      : restricted || rejected
        ? j.signOut
        : waiting
          ? j.verify
          : j.refresh;
  const label = t(
    approved
      ? 'continue'
      : ready
        ? 'goHome'
        : restricted || rejected
          ? 'signOut'
          : waiting
            ? more
              ? 'updateDocuments'
              : 'continueVerification'
            : 'checkStatus',
  );
  return (
    <Screen
      brand
      onBrandLongPress={onDev}
      footer={
        <>
          <ErrorNotice message={j.error && t(j.error)} />
          <Button label={label} onPress={action} loading={!!j.busy} />
          {waiting && <TextButton label={t('checkStatus')} onPress={j.refresh} />}
          {!success && !restricted && !rejected && (
            <TextButton label={t('signOut')} onPress={j.signOut} />
          )}
        </>
      }
    >
      <View style={styles.statusContent}>
        <View
          style={[
            styles.statusSymbol,
            { backgroundColor: success ? c.paleSuccess : rejected ? c.paleError : c.paleWarning },
          ]}
        >
          <Icon
            name={
              ready
                ? 'arrow-up-right'
                : approved
                  ? 'check'
                  : rejected || restricted
                    ? 'alert-circle'
                    : more
                      ? 'file-plus'
                      : 'clock'
            }
            size={48}
            color={success ? c.success : rejected ? c.error : c.warning}
          />
        </View>
        <Intro title={title} body={body} />
        {!restricted && (
          <Badge
            tone={success ? 'success' : rejected ? 'error' : 'warning'}
            label={t(
              approved
                ? 'verified'
                : ready
                  ? 'active'
                  : rejected
                    ? 'rejectedBadge'
                    : more
                      ? 'moreBadge'
                      : 'pendingBadge',
            )}
          />
        )}
      </View>
    </Screen>
  );
}
const styles = StyleSheet.create({
  note: { fontSize: 12, textAlign: 'center', marginTop: 24, lineHeight: 19 },
  statusContent: { flex: 1, justifyContent: 'center', paddingBottom: 64 },
  statusSymbol: {
    width: 104,
    height: 104,
    borderRadius: 52,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 40,
  },
});
