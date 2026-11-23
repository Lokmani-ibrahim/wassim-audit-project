// File persistence for the notification read state: one small JSON file per user in the app's
// document directory (<document>/notifications/read-<userId>.json). Not SecureStore: the data is
// not secret and can exceed the ~2 KB SecureStore values are reliable for (500 ids). Every file
// is deleted on sign-out. Nothing here is logged.
import { Directory, File, Paths } from 'expo-file-system';
import { createReadStateStore, type ReadStatePersistence } from './store';

const DIR = 'notifications';
// The user id is the backend UUID; anything else never becomes a path.
const USER_ID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

function fileFor(userId: string): File | null {
  if (!USER_ID.test(userId)) return null;
  return new File(new Directory(Paths.document, DIR), `read-${userId.toLowerCase()}.json`);
}

export const fileReadStatePersistence: ReadStatePersistence = {
  load(userId) {
    const file = fileFor(userId);
    if (!file || !file.exists) return null;
    return JSON.parse(file.textSync());
  },
  save(userId, state) {
    const file = fileFor(userId);
    if (!file) return;
    new Directory(Paths.document, DIR).create({ intermediates: true, idempotent: true });
    if (!file.exists) file.create();
    file.write(JSON.stringify(state));
  },
  clearAll() {
    const dir = new Directory(Paths.document, DIR);
    if (dir.exists) dir.delete();
  },
};

export const notificationReadState = createReadStateStore(fileReadStatePersistence);
