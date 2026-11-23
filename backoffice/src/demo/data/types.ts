export type Currency = 'EUR' | 'USD' | 'GBP' | 'AED' | 'MUR' | 'TND';
export type AccountStatus = 'ACTIVE' | 'PENDING_KYC' | 'SUSPENDED' | 'CLOSED';
export type KycStatus = 'NOT_STARTED' | 'PENDING' | 'IN_REVIEW' | 'APPROVED' | 'REJECTED' | 'RESUBMISSION_REQUIRED';
export type ProviderResult = 'GREEN' | 'RED-RETRY' | 'RED-FINAL' | 'pending';
export type TransactionType = 'incoming payment' | 'internal transfer' | 'conversion' | 'fee' | 'payout';
export type TransactionStatus = 'COMPLETED' | 'PENDING' | 'REVERSED' | 'FAILED';
export type AlertType = 'large transfer' | 'multiple failed logins' | 'new device' | 'KYC rejected' | 'reconciliation variance';

export interface HistoryEntry { at: string; label: string; by: string; reason?: string }
export interface Wallet { currency: Currency; balance: number }
export interface Note { at: string; author: string; text: string }
export interface User {
  id: string; name: string; email: string; country: string; status: AccountStatus; kycStatus: KycStatus;
  createdAt: string; wallets: Wallet[]; kycHistory: HistoryEntry[]; notes: Note[];
  loginHistory: { device: string; at: string; ip: string }[];
}
export interface KycCheck { name: 'ID front' | 'ID back' | 'Selfie' | 'Liveness'; result: 'Passed' | 'Needs review' | 'Missing' }
export interface KycCase {
  id: string; applicantId: string; userId: string; level: 'id-and-liveness'; submittedAt: string;
  status: KycStatus; providerResult: ProviderResult; assignedAgent: string; checks: KycCheck[]; history: HistoryEntry[];
}
export interface Transaction {
  id: string; userId: string; type: TransactionType; direction: 'IN' | 'OUT'; amount: number;
  currency: Currency; status: TransactionStatus; reference: string; at: string; flagged: boolean;
}
export interface Reconciliation { id: string; day: string; currency: Currency; bankTotal: number; ledgerTotal: number; difference: number; status: 'Matched' | 'Variance' }
export interface UnmatchedItem { id: string; day: string; currency: Currency; amount: number; description: string; justified: boolean; comment?: string }
export interface Alert { id: string; type: AlertType; severity: 'High' | 'Medium' | 'Low'; userId?: string; at: string; status: 'open' | 'closed'; comment?: string }
export interface TransactionFilters { search?: string; type?: TransactionType | ''; status?: TransactionStatus | ''; currency?: Currency | ''; from?: string; to?: string }
