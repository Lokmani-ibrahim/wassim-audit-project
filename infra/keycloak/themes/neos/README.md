# Keycloak login theme `neos`

CSS-only skin for the Keycloak login pages (sign-in, registration, password reset, errors).
It extends Keycloak's `keycloak` (v1) login theme, overrides no `.ftl` template, and adds
`login/resources/css/neos.css` with the NEOS colours and type from `mobile/src/theme/index.ts`.
No JavaScript, no build step.

To adopt it (backend developer), see `docs/api/INTEGRATION_STATUS.md`, request R3: a read-only
volume `./themes/neos:/opt/keycloak/themes/neos:ro` in `infra/keycloak/compose.yml`, and
`"loginTheme": "neos"` in `infra/keycloak/realm/neos-realm.json`.

To try it on a running local Keycloak without changing tracked files:

```sh
docker cp infra/keycloak/themes/neos neos-keycloak:/opt/keycloak/themes/neos
# start-dev disables theme caching. Restart the container only if the theme is not picked up.
# Then set it on the running realm only (admin API): PUT /admin/realms/neos {"loginTheme":"neos"}
```

The copied files live in the container's writable layer: they are gone if the container is
recreated (`docker compose up --force-recreate`), which is fine for a trial.
