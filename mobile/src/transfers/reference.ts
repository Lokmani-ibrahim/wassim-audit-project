// Deterministic ledger reference of a customer internal transfer (contract §5):
//   internal_transfer:ct_<senderUserId without dashes>_<idempotencyKey without dashes>
// Server: CustomerInternalTransferController (operationId) + FormanceLedgerProvider
// (operationType + ":" + operationId). Java's UUID.toString() is lowercase, so both parts are
// lowercased here too.
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function transferReference(senderUserId: string, idempotencyKey: string): string {
  for (const [name, value] of [
    ['senderUserId', senderUserId],
    ['idempotencyKey', idempotencyKey],
  ] as const)
    if (!UUID.test(value)) throw new Error(`${name} is not a UUID`);
  const strip = (value: string) => value.toLowerCase().replace(/-/g, '');
  return `internal_transfer:ct_${strip(senderUserId)}_${strip(idempotencyKey)}`;
}
