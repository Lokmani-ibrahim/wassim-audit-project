import { alerts, kycCases, reconciliation, transactions, unmatchedItems, users } from '../data/seed';
import type { AccountStatus, Alert, KycCase, KycStatus, Note, Reconciliation, Transaction, TransactionFilters, UnmatchedItem, User } from '../data/types';

// One replacement seam for future HTTP calls. The arrays are intentionally session-only.
const copy = <T,>(value: T): T => structuredClone(value);
const later = async <T,>(value: T): Promise<T> => copy(value);
export const listUsers = async (): Promise<User[]> => later(users);
export const getUser = async (id: string): Promise<User | undefined> => later(users.find(x => x.id === id));
export const listKycCases = async (): Promise<KycCase[]> => later(kycCases);
export const getKycCase = async (id: string): Promise<KycCase | undefined> => later(kycCases.find(x => x.id === id));
export const listTransactions = async (filters: TransactionFilters = {}): Promise<Transaction[]> => later(transactions.filter(x =>
  (!filters.search || `${x.id} ${x.reference} ${x.userId}`.toLowerCase().includes(filters.search.toLowerCase())) &&
  (!filters.type || x.type === filters.type) && (!filters.status || x.status === filters.status) && (!filters.currency || x.currency === filters.currency) &&
  (!filters.from || x.at.slice(0, 10) >= filters.from) && (!filters.to || x.at.slice(0, 10) <= filters.to)
));
export const getTransaction = async (id: string): Promise<Transaction | undefined> => later(transactions.find(x => x.id === id));
export const listReconciliation = async (): Promise<Reconciliation[]> => later(reconciliation);
export const listUnmatchedItems = async (): Promise<UnmatchedItem[]> => later(unmatchedItems);
export const listAlerts = async (): Promise<Alert[]> => later(alerts);
export const setUserStatus = async (id: string, status: AccountStatus, reason: string): Promise<void> => { const x = users.find(u => u.id === id); if (x) { x.status = status; x.notes.unshift({ at: new Date().toISOString(), author: 'Compliance officer', text: `Simulated ${status.toLowerCase()} · ${reason}` }); } };
export const addUserNote = async (id: string, text: string): Promise<Note | undefined> => { const x = users.find(u => u.id === id); const note = { at: new Date().toISOString(), author: 'Compliance officer', text }; x?.notes.unshift(note); return later(x ? note : undefined); };
export const decideKycCase = async (id: string, status: KycStatus, reason: string): Promise<void> => { const x = kycCases.find(c => c.id === id); if (!x) return; x.status = status; x.history.unshift({ at: new Date().toISOString(), label: status.replaceAll('_', ' '), by: 'Compliance officer', reason }); const user = users.find(u => u.id === x.userId); if (user) { user.kycStatus = status; user.kycHistory.unshift({ at: new Date().toISOString(), label: status.replaceAll('_', ' '), by: 'Compliance officer', reason }); if (status === 'APPROVED' && user.status === 'PENDING_KYC') user.status = 'ACTIVE'; } };
export const assignKycCaseToMe = async (id: string): Promise<void> => { const x = kycCases.find(c => c.id === id); if (x) x.assignedAgent = 'Compliance officer'; };
export const flagTransaction = async (id: string): Promise<void> => { const x = transactions.find(t => t.id === id); if (x) x.flagged = true; };
export const justifyUnmatchedItem = async (id: string, comment: string): Promise<void> => { const x = unmatchedItems.find(i => i.id === id); if (x) { x.justified = true; x.comment = comment; } };
export const closeAlert = async (id: string, comment: string): Promise<void> => { const x = alerts.find(a => a.id === id); if (x) { x.status = 'closed'; x.comment = comment; } };
