# NEOS: notes for Claude Code

## Project
Multi-currency fintech app (sponsor-bank model). Backend: Spring Boot at the repo root (Java 21, Spring Modulith, PostgreSQL + Flyway, Keycloak, Formance ledger, Sumsub, Microsoft Graph). Mobile: React Native + Expo in `mobile/`. Shared branch: `integration` (changes only through PRs).

I am the frontend developer. The backend is mainly owned by the backend developer.

## Ownership
- Never modify the backend developer's code, migrations, `application*.yml`, `infra/keycloak/compose.yml` or `neos-realm.json` unless the task explicitly says so. Backend contributions go in new, separate files following his conventions, through a PR he reviews.
- Findings about his code are reported (in the PR description or `docs/api/INTEGRATION_STATUS.md`), never fixed silently.

## How to work
- Read the relevant docs first: `docs/api/MOBILE_API_CONTRACT.md`, `docs/api/INTEGRATION_STATUS.md`, `docs/ENGINEERING_LOG.md`.
- Plan first and wait for approval before changing code. Don't commit or push unless asked.
- New branch per task from the latest `origin/integration`; run `git branch --unset-upstream` so a plain push can't reach `integration`.
- No new dependency without asking. Mocks stay dev-only (mock registry, release checks).
- Never claim something was tested unless it was run; mark the rest "not verified".

## Secrets and safety
- Never print, log or copy values from `.env`, tokens or passwords. Test passwords come through a temporary file, deleted at the end.
- Keep `MICROSOFT_GRAPH_ENABLED` and `NOTIFICATION_DISPATCHER_ENABLED` off unless the task is an email test.
- Never forge Sumsub webhooks. Never auto-retry a transfer with an unknown outcome.

## Local environment (Windows)
- Docker: Keycloak 8081, Formance 8083, backend Postgres 5434. Backend on 8080 via `scripts/run-backend-local.ps1` (loads the root `.env`). Metro on 8082 (`--clear`, not CI mode). `adb reverse` 8080/8081/8082.
- Start Metro with `NODE_OPTIONS=--dns-result-order=ipv4first`: with `--localhost` it otherwise listens on IPv6 only, `adb reverse` can't reach it and the app shows "unexpected end of stream".
- JDK 17 for Android (`NEOS_ANDROID_JAVA_HOME`), JDK 21 for the backend (Android Studio `jbr`).
- Limited RAM and disk: check free disk first (stop under 10 GB); emulator OFF during Gradle builds (`x86_64`, `--max-workers=1`); start things one at a time.

## Checks before reporting (mobile)
`npx tsc --noEmit`, `npm run lint`, `npm test`, `npm run check:release-bundle`, `npm run check:release-readiness`.

## Engineering log
`docs/ENGINEERING_LOG.md` records the frontend developer's work for the backend developer to read. Keep it current, clean and short.
At the end of any task that changes code, configuration or tests (not pure questions or reads):
- Add ONE row to the section 3 table (next WP number, date, one-line outcome) and ONE short WP paragraph (3–5 sentences: found, done, result, PR or commit).
- Section 5: only real decisions taken in the task. Section 6: only options explicitly set aside, with the reason.
- Update changed numbers: test counts, checks table (7.1), live scenarios actually run (7.3), open/done requests (9.1). Appendix: only new branches, PRs, key commits or documents.
- Backend developer changes appear only when integrated or tested in the task (e.g. "PR #64 integrated, error mapping adjusted").
- Facts only; no names ("frontend developer" / "backend developer"); no secrets or personal emails; no duplicates (update rows instead); don't rewrite past entries except to fix a factual error.
- Include the log update in the same commit or PR as the work.