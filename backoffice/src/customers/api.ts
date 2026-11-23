import type { StaffSession } from '../auth/StaffSession';

export type Customer = {
  userId: string;
  email: string;
  accountStatus: 'PENDING_KYC' | 'ACTIVE' | 'SUSPENDED' | 'CLOSED';
  kycStatus: 'NOT_STARTED' | 'PENDING' | 'IN_REVIEW' | 'APPROVED' | 'REJECTED' | 'RESUBMISSION_REQUIRED' | null;
  createdAt: string;
};
export type CustomerPage = { items: Customer[]; nextCursor: string | null };

const accounts = ['PENDING_KYC', 'ACTIVE', 'SUSPENDED', 'CLOSED'];
const kyc = ['NOT_STARTED', 'PENDING', 'IN_REVIEW', 'APPROVED', 'REJECTED', 'RESUBMISSION_REQUIRED'];
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const object = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

export function parseCustomer(value: unknown): Customer {
  if (!object(value) || Object.keys(value).sort().join(',') !==
      'accountStatus,createdAt,email,kycStatus,userId' ||
      typeof value.userId !== 'string' || !uuid.test(value.userId) ||
      typeof value.email !== 'string' || !value.email.includes('@') ||
      typeof value.accountStatus !== 'string' || !accounts.includes(value.accountStatus) ||
      (value.kycStatus !== null && (typeof value.kycStatus !== 'string' || !kyc.includes(value.kycStatus))) ||
      typeof value.createdAt !== 'string' ||
      !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/.test(value.createdAt) ||
      Number.isNaN(Date.parse(value.createdAt))) {
    throw new Error('Invalid customer response');
  }
  return value as Customer;
}

export function parsePage(value: unknown): CustomerPage {
  if (!object(value) || Object.keys(value).sort().join(',') !== 'items,nextCursor' ||
      !Array.isArray(value.items) || value.items.length > 100 ||
      (value.nextCursor !== null && (typeof value.nextCursor !== 'string' || !value.nextCursor))) {
    throw new Error('Invalid customer page');
  }
  const items = value.items.map(parseCustomer);
  if (new Set(items.map(item => item.userId)).size !== items.length) throw new Error('Duplicate customer');
  return { items, nextCursor: value.nextCursor as string | null };
}

export type Filters = { search: string; accountStatus: string; kycStatus: string };

export async function listCustomers(session: StaffSession, filters: Filters, cursor: string | null): Promise<CustomerPage> {
  const params = new URLSearchParams({ pageSize: '25' });
  if (filters.search.trim()) params.set('search', filters.search.trim());
  if (filters.accountStatus) params.set('accountStatus', filters.accountStatus);
  if (filters.kycStatus) params.set('kycStatus', filters.kycStatus);
  if (cursor) params.set('cursor', cursor);
  return parsePage(await (await session.get(`/backoffice/customers?${params}`)).json());
}

export async function getCustomer(session: StaffSession, userId: string): Promise<Customer> {
  if (!uuid.test(userId)) throw new Error('Invalid customer ID');
  return parseCustomer(await (await session.get(`/backoffice/customers/${userId}`)).json());
}
