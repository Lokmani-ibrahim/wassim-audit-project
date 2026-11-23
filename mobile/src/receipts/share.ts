// Renders receipt HTML to a PDF in the app cache, opens the system share sheet, and deletes the
// file afterwards. Nothing about the receipt is logged.
import * as Print from 'expo-print';
import * as Sharing from 'expo-sharing';
import { Directory, File, Paths } from 'expo-file-system';

// A4 at 72 PPI.
const A4 = { width: 595, height: 842 };
const RECEIPTS_DIR = 'receipts';
// expo-print writes its output under <cache>/Print (Android and iOS).
const PRINT_DIR = 'Print';

export type ShareReceiptResult = 'shared' | 'unavailable';

function quietly(action: () => void) {
  try {
    action();
  } catch {
    // Best effort: a leftover file in the cache is swept on the next receipt.
  }
}

// Any receipt left by an earlier run (crash, app killed while the share sheet was open).
export function removeReceiptFiles() {
  quietly(() => {
    const dir = new Directory(Paths.cache, RECEIPTS_DIR);
    if (dir.exists) dir.delete();
  });
  quietly(() => {
    const dir = new Directory(Paths.cache, PRINT_DIR);
    if (!dir.exists) return;
    for (const entry of dir.list())
      if (entry instanceof File && entry.name.toLowerCase().endsWith('.pdf')) entry.delete();
  });
}

// File name shown by share targets. The id is a JSON integer; anything else gets a fixed name.
export function receiptFileName(transactionId: number): string {
  const id = Number.isSafeInteger(transactionId) && transactionId >= 0 ? String(transactionId) : '';
  return id ? `NEOS-receipt-${id}.pdf` : 'NEOS-receipt.pdf';
}

export async function shareReceipt(
  html: string,
  transactionId: number,
  dialogTitle: string,
): Promise<ShareReceiptResult> {
  if (!(await Sharing.isAvailableAsync())) return 'unavailable';
  removeReceiptFiles();
  const printed = await Print.printToFileAsync({ html, ...A4 });
  const file = new File(printed.uri);
  try {
    const dir = new Directory(Paths.cache, RECEIPTS_DIR);
    dir.create({ intermediates: true, idempotent: true });
    // move() updates file.uri to the new location.
    file.move(new File(dir, receiptFileName(transactionId)));
    await Sharing.shareAsync(file.uri, {
      mimeType: 'application/pdf',
      UTI: 'com.adobe.pdf',
      dialogTitle,
    });
    return 'shared';
  } finally {
    quietly(() => {
      if (file.exists) file.delete();
    });
    removeReceiptFiles();
  }
}
