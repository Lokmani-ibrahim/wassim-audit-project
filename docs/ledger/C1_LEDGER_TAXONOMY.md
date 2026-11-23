# Neos Ledger Taxonomy v1

Status: C1 implementation baseline.

## Principles

- One Formance ledger per environment.
- Do not create one Formance ledger per currency.
- PostgreSQL stores application state only.
- PostgreSQL never stores authoritative financial balances.
- Formance is the source of truth for balances and financial postings.
- Account addresses are constructed server-side only.
- Customer identifiers use the internal Neos UUID.
- Customer PII must never appear in a ledger account address.
- Currency in the account address must match the Formance asset.

## Account families

```text
users:{userId}:wallet:{currency}:available

users:{userId}:wallet:{currency}:holds:{operationId}

bank:{bankId}:master:{currency}

suspense:incoming:{currency}

platform:fees:{currency}