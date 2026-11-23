// App wiring for the pure FX engine: real services, secure storage, platform UUIDs.
// Convert is a sandbox demo: reachable only in a sandbox build (fxAvailable), never otherwise.
import * as Crypto from 'expo-crypto';
import { config, isSandbox } from '../config';
import { services } from '../services';
import { storage } from '../services/storage';
import { createFxEngine, readPendingConversion, type PendingConversion } from './engine';

export function fxAvailable(): boolean {
  return isSandbox(config) && !config.mocks.has('auth');
}

export function fxEngineFor(userId: string) {
  return createFxEngine(userId, {
    quote: (request) => services.fx.quote(request),
    convert: (request) => services.fx.convert(request),
    findByKey: (key) => services.fx.findByKey(key),
    store: storage,
    uuid: () => Crypto.randomUUID(),
    now: () => Date.now(),
  });
}

export async function pendingConversionOf(userId: string): Promise<PendingConversion | null> {
  try {
    return await readPendingConversion(storage, userId);
  } catch {
    return null;
  }
}
