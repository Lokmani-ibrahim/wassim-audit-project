import * as SecureStore from 'expo-secure-store';
import * as Crypto from 'expo-crypto';

export const storage = {
  async read<T>(key: string): Promise<T | null> {
    const value = await SecureStore.getItemAsync(key);
    if (!value) return null;
    try {
      return JSON.parse(value) as T;
    } catch {
      await SecureStore.deleteItemAsync(key);
      return null;
    }
  },
  async write(key: string, value: unknown) {
    await SecureStore.setItemAsync(key, JSON.stringify(value));
  },
  async remove(key: string) {
    await SecureStore.deleteItemAsync(key);
  },
};
export async function installationId() {
  let id = await storage.read<string>('neos.installation');
  if (!id) {
    id = Crypto.randomUUID();
    await storage.write('neos.installation', id);
  }
  return id;
}
