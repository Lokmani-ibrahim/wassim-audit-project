import type { Profile, Route } from '../types/domain';

// Backend state wins over local navigation; acknowledgement screens are session-local.
// `home` means "enter the authenticated area" (navigation/AuthenticatedApp.tsx), which has
// its own React Navigation stack; it is only reachable with KYC APPROVED + account ACTIVE.
//
// KYC routing (docs/api/SUMSUB_MOBILE_INTEGRATION_PLAN.md, phase 2):
// - SUSPENDED + RESUBMISSION_REQUIRED ("more info") stays on the resubmission screen: the
//   backend lets a KYC-reverification suspension resubmit through the SDK, but GET /api/profile
//   does not expose the suspension reason. The app therefore tries POST /api/kyc/sdk-token and,
//   on ACCOUNT_RESTRICTED (or a 403 without a code), sets `kycRestricted` => restricted.
// - Any other SUSPENDED state and CLOSED => restricted (no SDK).
export interface RouteOptions {
  // The backend refused an SDK session for account reasons in this session.
  kycRestricted?: boolean;
}
export function routeFor(
  profile: Profile | null,
  acknowledged = false,
  options: RouteOptions = {},
): Route {
  if (!profile) return 'welcome';
  if (profile.account === 'CLOSED') return 'restricted';
  if (profile.account === 'SUSPENDED') {
    const mayResubmit =
      profile.onboarded && profile.kyc === 'MORE_INFO_REQUIRED' && !options.kycRestricted;
    return mayResubmit ? 'moreInfo' : 'restricted';
  }
  if (!profile.onboarded && profile.identity.onboardingEligibility === 'CONFLICT')
    return 'onboardingConflict';
  if (!profile.onboarded) return 'intro';
  switch (profile.kyc) {
    case 'NOT_STARTED':
      return 'kycIntro';
    case 'PENDING':
    case 'IN_REVIEW':
      return 'pending';
    case 'REJECTED':
      return 'rejected';
    case 'MORE_INFO_REQUIRED':
      return 'moreInfo';
    case 'APPROVED':
      return profile.account === 'ACTIVE' ? (acknowledged ? 'home' : 'approved') : 'pending';
  }
}
// May enter the authenticated area.
export function isHomeEligible(profile: Profile | null): boolean {
  return !!profile && profile.kyc === 'APPROVED' && profile.account === 'ACTIVE';
}
// "Acknowledged" decides whether an eligible profile goes straight to home (true) or first
// through the "You're verified" / "Your account is ready" screens (false).
// - Session start (sign-in, restore): true. A returning approved customer goes straight home.
// - Status update inside the session (KYC submit/refresh, profile re-read): becomes false only
//   when the app OBSERVES the transition to APPROVED + ACTIVE; otherwise it is unchanged.
export const ACKNOWLEDGED_ON_SESSION_START = true;
export function acknowledgedAfterUpdate(
  previous: Profile | null,
  next: Profile | null,
  current: boolean,
): boolean {
  if (isHomeEligible(next) && !isHomeEligible(previous)) return false;
  return current;
}
export const previousRoute: Partial<Record<Route, Route>> = {
  createAccount: 'welcome',
  signIn: 'welcome',
  details: 'intro',
  review: 'details',
};
// Screens that only show backend state (no form progress to keep): a foreground re-check may
// replace them with the fresh status (ISSUE-6: the account-unavailable screen used to stay
// until a cold start).
export const STATUS_ROUTES: readonly Route[] = [
  'home',
  'kycIntro',
  'pending',
  'moreInfo',
  'rejected',
  'restricted',
];
// KYC waiting screens: the status is polled (kyc/polling.ts) while one is shown.
export const POLLING_ROUTES: readonly Route[] = ['pending', 'moreInfo'];
export function validPhone(phone: string): boolean {
  const value = phone.trim();
  const digits = value.replace(/\D/g, '');
  return !value || (/^\+?[\d ()-]{7,32}$/.test(value) && digits.length >= 7 && digits.length <= 15);
}
