import type { Alert, Currency, KycCase, KycStatus, Reconciliation, Transaction, UnmatchedItem, User } from './types';

export const currencies: Currency[] = ['EUR', 'USD', 'GBP', 'AED', 'MUR', 'TND'];
const day = (daysAgo: number, hour = 10) => { const date = new Date(); date.setDate(date.getDate() - daysAgo); date.setHours(hour, 24, 0, 0); return date.toISOString(); };
const names = ['Ari Senva', 'Mira Calden', 'Noel Varin', 'Lina Tevra', 'Emil Rosven', 'Sana Delor', 'Theo Mavren', 'Nia Corven', 'Ivo Narel', 'Elsa Virel', 'Rami Solven', 'Ada Melrin', 'Luca Fenor', 'Yara Cobel', 'Oren Talvi', 'Lea Savin', 'Milo Deran', 'Ines Ravol', 'Nero Malven', 'Tia Venrel', 'Evan Sorel', 'Rina Kelvor', 'Otis Valen', 'Nola Semrin', 'Amir Farel'];
const countries = ['France', 'Germany', 'United Kingdom', 'United Arab Emirates', 'Mauritius', 'Tunisia'];
const kycStatuses: KycStatus[] = ['APPROVED', 'APPROVED', 'APPROVED', 'APPROVED', 'PENDING', 'IN_REVIEW', 'REJECTED', 'RESUBMISSION_REQUIRED', 'NOT_STARTED'];

export const users: User[] = names.map((name, i) => {
  const kycStatus = kycStatuses[i % kycStatuses.length];
  const status = i === 18 || i === 23 ? 'SUSPENDED' : i === 24 ? 'CLOSED' : kycStatus === 'APPROVED' ? 'ACTIVE' : 'PENDING_KYC';
  return {
    id: `USR-${String(i + 1).padStart(4, '0')}`, name, email: `${name.toLowerCase().replace(' ', '.')}@example.com`, country: countries[i % countries.length],
    status, kycStatus, createdAt: day(42 - i),
    wallets: currencies.map((currency, c) => ({ currency, balance: Math.round((i + 1) * (c + 2) * 137.65 * 100) / 100 })),
    kycHistory: [{ at: day(26 - i % 12), label: 'Application created', by: 'System' }, { at: day(13 - i % 8), label: kycStatus.replaceAll('_', ' '), by: 'KYC provider' }],
    notes: [], loginHistory: [{ device: i % 2 ? 'Desktop Â· Chrome' : 'Mobile Â· Safari', at: day(i % 8, 14), ip: `192.0.2.${20 + i}` }, { device: 'Desktop Â· Edge', at: day(9 + i % 7), ip: `198.51.100.${30 + i}` }]
  };
});

export const kycCases: KycCase[] = users.slice(4, 19).map((user, i) => ({
  id: `KYC-${String(i + 1).padStart(4, '0')}`, applicantId: `APP-8${String(i + 1).padStart(4, '0')}`, userId: user.id,
  level: 'id-and-liveness', submittedAt: day(i % 12, 11), status: user.kycStatus,
  providerResult: user.kycStatus === 'APPROVED' ? 'GREEN' : user.kycStatus === 'REJECTED' ? 'RED-FINAL' : user.kycStatus === 'RESUBMISSION_REQUIRED' ? 'RED-RETRY' : 'pending',
  assignedAgent: i % 3 === 0 ? 'Compliance officer' : 'Unassigned',
  checks: (['ID front', 'ID back', 'Selfie', 'Liveness'] as const).map((name, c) => ({ name, result: user.kycStatus === 'APPROVED' ? 'Passed' as const : c === 3 ? 'Needs review' as const : user.kycStatus === 'NOT_STARTED' ? 'Missing' as const : 'Passed' as const })),
  history: [{ at: day(i % 12, 11), label: 'Submitted', by: 'Applicant' }, { at: day(i % 12, 12), label: user.kycStatus.replaceAll('_', ' '), by: 'KYC provider' }]
}));

const transactionTypes = ['incoming payment', 'internal transfer', 'conversion', 'fee', 'payout'] as const;
const transactionStatuses = ['COMPLETED', 'COMPLETED', 'COMPLETED', 'PENDING', 'REVERSED', 'FAILED'] as const;
export const transactions: Transaction[] = Array.from({ length: 80 }, (_, i) => ({
  id: `TXN-${String(1001 + i)}`, userId: users[(i * 7) % users.length].id, type: transactionTypes[i % 5],
  direction: i % 5 === 0 ? 'IN' : 'OUT', amount: i % 5 === 3 ? 4.5 : Math.round((85 + ((i * 173) % 4800)) * 100) / 100,
  currency: currencies[i % 6], status: transactionStatuses[i % 6], reference: `NEOS-SBX-${String(1001 + i)}`,
  at: day(i % 14, 8 + i % 11), flagged: false
}));

export const reconciliation: Reconciliation[] = Array.from({ length: 7 }, (_, i) => {
  const currency = currencies[i % currencies.length]; const ledgerTotal = 32000 + i * 3482.75; const difference = i === 2 ? 125.4 : i === 5 ? -42.75 : 0;
  return { id: `REC-${i + 1}`, day: day(i).slice(0, 10), currency, bankTotal: ledgerTotal + difference, ledgerTotal, difference, status: difference ? 'Variance' : 'Matched' };
});
export const unmatchedItems: UnmatchedItem[] = [
  { id: 'UNM-001', day: reconciliation[2].day, currency: reconciliation[2].currency, amount: 125.4, description: 'Incoming transfer Â· unknown reference', justified: false },
  { id: 'UNM-002', day: reconciliation[5].day, currency: reconciliation[5].currency, amount: 42.75, description: 'Payout fee Â· timing difference', justified: false }
];
const alertTypes = ['large transfer', 'multiple failed logins', 'new device', 'KYC rejected', 'reconciliation variance'] as const;
export const alerts: Alert[] = Array.from({ length: 10 }, (_, i) => ({
  id: `ALT-${String(i + 1).padStart(3, '0')}`, type: alertTypes[i % 5], severity: i % 3 === 0 ? 'High' : i % 3 === 1 ? 'Medium' : 'Low',
  userId: i % 5 === 4 ? undefined : users[(i * 3) % users.length].id, at: day(i, 15), status: i < 7 ? 'open' : 'closed'
}));
