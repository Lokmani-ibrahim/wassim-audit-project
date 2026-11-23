// HARNAIS DE CAPTURE (copie isolée de l'audit, absent du projet) : expo-secure-store n'existe pas
// sur le web ; ce stockage en mémoire permet d'afficher les écrans dans un navigateur.
const memory = new Map<string, string>();
export const storage = {
  async read<T>(key: string): Promise<T | null> {
    const value = memory.get(key);
    if (!value) return null;
    try { return JSON.parse(value) as T; } catch { memory.delete(key); return null; }
  },
  async write(key: string, value: unknown) { memory.set(key, JSON.stringify(value)); },
  async remove(key: string) { memory.delete(key); },
};
export async function installationId() {
  let id = await storage.read<string>('neos.installation');
  if (!id) { id = crypto.randomUUID(); await storage.write('neos.installation', id); }
  return id;
}
