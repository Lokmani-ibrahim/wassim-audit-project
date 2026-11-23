// App wiring for the pure transfer engine: real services, secure storage, platform UUIDs.
import * as Crypto from 'expo-crypto';
import { services } from '../services';
import { storage } from '../services/storage';
import { createTransferEngine, readPending, type PendingTransfer } from './engine';

export function transferEngineFor(senderUserId: string) {
  return createTransferEngine(senderUserId, {
    send: (request) => services.transfers.send(request),
    list: (currency, cursor, pageSize) => services.transactions.list(currency, cursor, pageSize),
    store: storage,
    uuid: () => Crypto.randomUUID(),
    now: () => Date.now(),
  });
}

export async function pendingTransferOf(senderUserId: string): Promise<PendingTransfer | null> {
  try {
    return await readPending(storage, senderUserId);
  } catch {
    return null;
  }
}
