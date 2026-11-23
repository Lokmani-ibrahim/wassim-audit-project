// Cursor paging over GET /api/transactions (contract §6). Pure: the page fetcher is injected.
//
// The backend filters Formance transactions AFTER paging them, so a page can hold fewer items
// than pageSize, including ZERO items with hasMore: true. Paging is therefore driven by
// hasMore/nextCursor only, never by item counts, and every loop is bounded.
//
// Ordering is whatever the server returns (Formance default; newest-first is UNVERIFIED
// server-side). Items are kept in the order received and never re-sorted here.
import type { Currency, TransactionItem, TransactionPage } from '../api/contract';

export type ListTransactions = (
  currency: Currency,
  cursor: string | null,
  pageSize: number,
) => Promise<TransactionPage>;

export interface LoadedPage {
  items: TransactionItem[];
  hasMore: boolean;
  nextCursor: string | null;
}

// Backend pageSize must be 1..100 (out of range is a 500, not a 400). Ignored with a cursor.
export const DEFAULT_PAGE_SIZE = 20;
export const MAX_PAGE_SIZE = 100;
// Consecutive empty pages followed in one "load" before handing control back to the user.
export const MAX_EMPTY_HOPS = 5;
// Reference search: at most 10 pages of 100 (≈1,000 transactions in that currency).
export const SEARCH_MAX_PAGES = 10;

// hasMore with no cursor would loop forever on the first page: treat it as the end.
function normalise(page: TransactionPage): LoadedPage {
  const hasMore = page.hasMore && !!page.nextCursor;
  return { items: page.items, hasMore, nextCursor: hasMore ? page.nextCursor : null };
}

// One user-visible "page": follows empty pages (bounded) until at least one item arrives or the
// history ends. If the hop budget runs out, returns no items with hasMore still true, so the
// UI offers "Load more" instead of claiming the history is empty.
export async function loadPage(
  list: ListTransactions,
  currency: Currency,
  cursor: string | null,
  options: { pageSize?: number; maxHops?: number } = {},
): Promise<LoadedPage> {
  const pageSize = options.pageSize ?? DEFAULT_PAGE_SIZE;
  const maxHops = options.maxHops ?? MAX_EMPTY_HOPS;
  let next = cursor;
  for (let hop = 0; ; hop += 1) {
    const page = normalise(await list(currency, next, pageSize));
    if (page.items.length > 0 || !page.hasMore || hop + 1 >= maxHops) return page;
    next = page.nextCursor;
  }
}

// First `count` items (in server order), across as many pages as needed, bounded.
// exhausted = the whole history was read: only then does "no items" mean "no activity".
export async function recentItems(
  list: ListTransactions,
  currency: Currency,
  count: number,
  options: { pageSize?: number; maxPages?: number } = {},
): Promise<{ items: TransactionItem[]; exhausted: boolean }> {
  const maxPages = options.maxPages ?? MAX_EMPTY_HOPS;
  const items: TransactionItem[] = [];
  let cursor: string | null = null;
  let exhausted = false;
  for (let pageIndex = 0; pageIndex < maxPages && items.length < count; pageIndex += 1) {
    const page = normalise(await list(currency, cursor, options.pageSize ?? 10));
    items.push(...page.items);
    if (!page.hasMore) {
      exhausted = true;
      break;
    }
    cursor = page.nextCursor;
  }
  return { items: items.slice(0, count), exhausted };
}

export type SearchResult =
  | { found: true; item: TransactionItem }
  // exhausted: the whole history was read (true) or the page budget ran out first (false).
  // Either way absence is NOT proof that the transfer failed (§5 "Polling the outcome").
  | { found: false; exhausted: boolean };

// Looks for a transaction by its ledger id (notification → transaction detail). Bounded like
// findByReference; absence only means "not in the pages read".
export async function findByTransactionId(
  list: ListTransactions,
  currency: Currency,
  transactionId: number,
  options: { maxPages?: number } = {},
): Promise<SearchResult> {
  const maxPages = options.maxPages ?? SEARCH_MAX_PAGES;
  let cursor: string | null = null;
  for (let pageIndex = 0; pageIndex < maxPages; pageIndex += 1) {
    const page = normalise(await list(currency, cursor, MAX_PAGE_SIZE));
    const item = page.items.find((candidate) => candidate.transactionId === transactionId);
    if (item) return { found: true, item };
    if (!page.hasMore) return { found: false, exhausted: true };
    cursor = page.nextCursor;
  }
  return { found: false, exhausted: false };
}

// Looks for a transaction by its exact reference. Any fetch error propagates to the caller.
export async function findByReference(
  list: ListTransactions,
  currency: Currency,
  reference: string,
  options: { maxPages?: number } = {},
): Promise<SearchResult> {
  const maxPages = options.maxPages ?? SEARCH_MAX_PAGES;
  let cursor: string | null = null;
  for (let pageIndex = 0; pageIndex < maxPages; pageIndex += 1) {
    const page = normalise(await list(currency, cursor, MAX_PAGE_SIZE));
    const item = page.items.find((candidate) => candidate.reference === reference);
    if (item) return { found: true, item };
    if (!page.hasMore) return { found: false, exhausted: true };
    cursor = page.nextCursor;
  }
  return { found: false, exhausted: false };
}
