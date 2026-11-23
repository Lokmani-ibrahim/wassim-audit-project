#!/bin/sh
# Démarre l'application mobile (copie isolée) en mode web + mocks, port 8090.
cd "$1" || exit 1
export CI=1 BROWSER=none EXPO_NO_TELEMETRY=1 EXPO_PUBLIC_MOCK_FEATURES=all EXPO_PUBLIC_APP_ENV=development \
  EXPO_PUBLIC_API_URL=http://localhost:8080 EXPO_PUBLIC_KEYCLOAK_ISSUER=http://localhost:8081/realms/neos \
  EXPO_PUBLIC_KEYCLOAK_CLIENT_ID=neos-mobile EXPO_PUBLIC_REDIRECT_URI=neos://auth EXPO_PUBLIC_TERMS_VERSION=1.0 \
  EXPO_PUBLIC_DEMO_KYC_RESULT=APPROVED
exec npx expo start --web --port 8090 --localhost --clear
