// Sections 8 à 16 + annexes
const L = require('./lib');
const { C, P, H1, H2, bullets, table, callout, code, figure, spacer, sev, st, gallery } = L;
const S = process.env.SCRATCH;

function s8() {
  return [
    H1('8. Audit des technologies'),
    table(['Couche', 'Actuel', 'Constat (avantages / limites)', 'Recommandation', 'Migration'], [
      ['Backend', 'Spring Boot 4.1, Java 21, Spring Modulith 2.1', 'Versions récentes ; écosystème mûr pour la fintech (transactions, sécurité, observabilité). Code de qualité et très testé. Limite : verbosité, démarrage ~10 s.', st('Conserver', C.green), 'Aucune'],
      ['Base de données', 'PostgreSQL + Flyway', 'ACID, contraintes uniques pour l’idempotence, index partiels, `SKIP LOCKED` exploités ; Formance repose aussi sur PostgreSQL.', st('Conserver', C.green), 'Aucune'],
      ['Accès aux données', 'JPA (5 dépôts) + JdbcTemplate (14 classes)', 'SQL explicite pour les opérations critiques : pertinent mais non documenté.', 'Écrire la règle d’usage', 'S'],
      ['API', 'REST JSON, contrat en Markdown', 'Pas d’OpenAPI généré ; erreurs hétérogènes (codes uniquement dans le module KYC).', 'springdoc-openapi + ProblemDetail (RFC 9457)', 'S–M'],
      ['Identité', 'Keycloak 26.7.2', 'Choix standard et adapté ; configuration du realm trop permissive.', st('Conserver, durcir', C.green), 'S'],
      ['Web', 'React 18.3, Vite 6.4', 'Application petite, testée, build léger. React 19 et Vite 8 disponibles.', st('Conserver', C.green) , 'Montée M'],
      ['Mobile', 'React Native 0.83, Expo SDK 55', 'Déjà la stack cible souhaitée ; SDK 58 disponible ; vulnérabilités dans l’outillage Expo.', st('Conserver', C.green), 'Montée M'],
      ['Grand livre', 'Formance Ledger', 'Double entrée éprouvée ; couplage direct dans 8 services.', 'Isoler derrière un port', 'M'],
      ['Tests', 'JUnit 5, Testcontainers, ArchUnit, node:test, Vitest', 'Couverture fonctionnelle élevée ; suite backend non autonome, tests conteneurs optionnels.', 'Profil de test + CI', 'S'],
      ['Infrastructure', 'Compose Keycloak seul', 'Pas d’image applicative, pas de CI/CD, pas de supervision.', 'Docker + Compose + GitHub Actions', 'M'],
    ], [1300, 1900, 3138, 1800, 1500], { firstBold: true, size: 17 }),
    spacer(120),
    H2('8.1 Évaluation de la stack cible proposée'),
    table(['Technologie proposée', 'Verdict', 'Justification'], [
      ['NestJS (backend)', st('Non retenu', C.red), 'Réécrire 21 682 lignes de code et 35 646 lignes de tests Spring pour un gain fonctionnel nul, avec un risque élevé de régression sur des flux financiers. Spring Boot couvre déjà tous les besoins (sécurité OAuth2, transactions, Modulith). NestJS ne serait pertinent que pour une nouvelle base de code TypeScript sans existant.'],
      ['MongoDB (base de données)', st('Non retenu', C.red), 'Les données sont relationnelles et transactionnelles : idempotence par contraintes uniques, verrouillage `FOR UPDATE SKIP LOCKED`, audit de rapprochement. Formance impose PostgreSQL. MongoDB affaiblirait les garanties d’intégrité sans bénéfice identifié.'],
      ['React (web)', st('Déjà en place', C.green), 'Conserver ; planifier React 19 et Vite récents en même temps que l’extension du backoffice.'],
      ['React Native + Expo (mobile)', st('Déjà en place', C.green), 'Conserver ; monter le SDK Expo pour corriger les vulnérabilités de l’outillage et suivre React Native.'],
    ], [2200, 1500, 5938], { firstBold: true }),
    spacer(100),
    callout('Conclusion technologique', [
      'Aucune migration de stack n’est nécessaire : la stack actuelle correspond déjà, pour le front, à la cible souhaitée, et le backend Spring Boot est plus adapté qu’un remplacement NestJS/MongoDB pour un produit financier.',
      'L’effort doit porter sur la configuration, l’industrialisation et la consolidation, pas sur une réécriture.',
    ], 'green'),
  ];
}

function s9() {
  return [
    H1('9. Docker et infrastructure'),
    table(['Élément', 'Constat', 'Recommandation'], [
      ['Dockerfile backend', st('Absent', C.red), 'Multi-étapes : build `maven:3.9-eclipse-temurin-21` → exécution `eclipse-temurin:21-jre`, utilisateur non-root, JAR en couches, `HEALTHCHECK` sur `/actuator/health`.'],
      ['Dockerfile backoffice', st('Absent', C.red), 'Build Node 22 → `nginx:alpine` servant `dist/` et relayant `/api` (même origine exigée par `config.ts`).'],
      ['`infra/keycloak/compose.yml`', 'Keycloak 26.7.2 en `start-dev` + `postgres:18` ; healthcheck et `depends_on` corrects ; volume nommé.', 'Identifiants en clair (SEC-07), port exposé sur toutes les interfaces, thème non monté, `start-dev` inutilisable en production.'],
      ['PostgreSQL applicatif, Formance', st('Non fournis', C.red), 'Les ajouter au compose avec healthchecks ; sans eux, un nouveau poste ne peut pas lancer le projet.'],
      ['Variables d’environnement', '`.env.example` incomplet : issuer JWT et variables backoffice absents ; ports contradictoires (8080/8082).', 'Un `.env.example` par service, cohérent avec le compose.'],
      ['Santé / logs', 'Actuator expose `health` avec groupes liveness/readiness (vérifié) ; 16 instructions de log pour 21 682 lignes.', 'Logs JSON corrélés (traceId), métriques Prometheus.'],
      ['Production', 'Profil `prod` fail-fast (vérifié) ; Flyway désactivé en prod, scripts `ops/` manuels.', 'Documenter la procédure de migration ou l’exécuter comme job.'],
    ], [2100, 3600, 3938], { firstBold: true, size: 17 }),
    spacer(120),
    H2('9.1 Kubernetes : pertinence'),
    P('Aucune configuration Kubernetes n’existe et ce n’est **pas une priorité**. Le backend doit aujourd’hui fonctionner en instance unique : la limitation de débit est en mémoire (documenté dans `RecipientLookupRateLimiter`) et le volume d’utilisateurs est celui d’un pilote. Docker Compose, derrière un reverse proxy TLS, suffit pour la recette et une première production. Kubernetes deviendra pertinent si plusieurs instances, l’autoscaling ou des déploiements sans interruption sont exigés ; les probes liveness/readiness déjà exposées faciliteront ce passage (Deployment, Service, Ingress, ConfigMap/Secret, requests/limits).'),
    code([
      '# Esquisse — backend/Dockerfile (multi-étapes)',
      'FROM maven:3.9-eclipse-temurin-21 AS build',
      'WORKDIR /src',
      'COPY pom.xml . && RUN mvn -B -q dependency:go-offline',
      'COPY src ./src && RUN mvn -B -q -DskipTests package',
      'FROM eclipse-temurin:21-jre',
      'RUN useradd -r -u 10001 app && mkdir /app && chown app /app',
      'USER app',
      'COPY --from=build /src/target/*.jar /app/app.jar',
      'HEALTHCHECK CMD wget -qO- http://localhost:8080/actuator/health || exit 1',
      'ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]',
    ]),
  ];
}

function s10() {
  return [
    H1('10. Git et organisation du versioning'),
    P('Deux sources ont été analysées : le dépôt local audité (`main` uniquement, 3 commits : « Initial commit », « addf files », « Add missing project files… ») — un instantané sans l’historique d’origine — et les trois captures de la page « Active branches » du dépôt GitHub d’origine (dossier `images_version_git`), synthétisées ci-dessous.'),
    H2('10.1 Branches observées par catégorie'),
    table(['Catégorie', 'Branches (écart « Ahead » avec main)', 'Nb'], [
      ['Tronc', '`integration` (125), `dummy` (13)', '2'],
      ['Fondations backend', '`feat/a1-backend-foundation` (2), `a2-keycloak-security` (6), `a3-postgres-schema` (4), `c1-formance-ledger` (14), `c2-wallet-provisioning` (15), `c3-synthetic-credit` (14), `c4-transaction-history-notifications` (17)', '7'],
      ['Fonctionnalités backend', '`customer-read-apis` (50), `sumsub-kyc-backend` (43), `kyc-stable-error-codes` (76), `kyc-mobile-sdk-session` (68), `notifications-feed-api` (92), `bank-simulation-topup-payout` (104), `fx-01-sandbox-quotes` (90), `fx-02a-persisted-quotes` (98), `fx-02b-bank-aligned-evidence` (103), `fx-02b-conversion-lifecycle` (100)', '10'],
      ['Application mobile', '`frontend` (17), `frontend-integration` (23), `mobile-real-integration` (49), `mobile-sumsub-sdk` (83), `mobile-notifications` (93), `mobile-pdf-receipts` (86), `mobile-visible-currencies` (115)', '7'],
      ['Backoffice', '`backoffice-package-a` (116), `-b` (118), `-c` (120), `backoffice-demo` (116), `backoffice-usability` (122), `kyc-backoffice` (**128**)', '6'],
      ['Corrections', '`fix/sec-01-config-and-build-profiles` (14), `home-raw-text` (17), `backend-security-hardening` (57), `sumsub-upstream-resilience` (75), `keycloak-mobile-realm` (88), `fx-conversion-bugs` (107), `android-onboarding-identity-conflict` (124), `wallet-provisioning-recovery` (125)', '8'],
      ['Docs / outillage / tests', '`docs/engineering-log` (90), `docs/integration-test-pr55` (64), `chore/npm-audit` (55), `chore/fund-test-user-script` (125), `refactor/centralize-mocks` (16), `test/sumsub-sdk-http-sandbox` (70)', '6'],
    ], [1800, 7238, 600], { firstBold: true, size: 17 }),
    H2('10.2 Points positifs'),
    ...bullets([
      'Convention de nommage claire et respectée : `feat/`, `fix/`, `docs/`, `chore/`, `refactor/`, `test/`.',
      'Une branche par tâche et intégration par Pull Request vers `integration` (PR #49 à #80 citées dans `docs/ENGINEERING_LOG.md`).',
      'Lots empilés (A1→A3, C1→C4, FX-01→FX-02b, backoffice A→C) qui facilitent la revue.',
    ]),
    H2('10.3 Problèmes'),
    ...bullets([
      '**« main » abandonnée** : toutes les branches sont « Behind 1 » et `integration` a 125 commits d’avance ; la branche par défaut ne reflète pas le code réel.',
      '**46 branches actives lisibles**, dont beaucoup déjà intégrées et jamais supprimées : bruit et risque de partir d’une base obsolète.',
      '**Travail hors tronc** : `feat/kyc-backoffice` (128) dépasse `integration` (125).',
      '**Aucune CI** : colonne « Check status » vide, aucun `.github/workflows` ; le journal d’ingénierie indique « CI checks on pull requests : Deferred ». Conséquence mesurée : 2 classes de tests cassées sur la branche intégrée.',
      '**Ni tag ni release** ; branche `dummy` au nom non explicite servant de base à une pile.',
      '**Dépôt audité** : historique perdu, messages non descriptifs (« addf files »), `mvnw` sans bit exécutable, médias lourds versionnés (vidéo 57 Mo, 2 PNG de 1,1 Mo non utilisés ; `.git` = 55 Mo).',
      'Toutes les branches affichent « 3 days ago » : les dates ne permettent pas de juger l’activité réelle (poussée groupée probable).',
    ]),
    H2('10.4 Modèle recommandé (Git Flow adapté)'),
    ...figure(`${S}/diagrams/git-flow-cible.png`, 'Workflow cible : develop (= integration) comme tronc d’équipe, main pour la production taguée.'),
    table(['Branche', 'Usage', 'Règles'], [
      ['`main`', 'Code en production', 'Protégée ; fusion uniquement depuis `release/*` ou `hotfix/*` ; tag `vX.Y.Z` à chaque mise en production.'],
      ['`develop` (renommer ou conserver `integration`)', 'Tronc d’intégration', 'Protégée ; PR obligatoire, CI verte, 1 revue (CODEOWNERS backend/mobile) ; déploiement automatique en environnement dev.'],
      ['`feature/*`, `fix/*`, `docs/*`, `chore/*`', 'Travail court (< 1 semaine)', 'Créées depuis `develop`, squash-merge, suppression automatique après fusion ; Conventional Commits.'],
      ['`release/x.y`', 'Stabilisation', 'Déployée en recette ; seuls des correctifs ; fusion dans `main` (tag) puis report dans `develop`.'],
      ['`hotfix/*`', 'Urgence production', 'Depuis `main` ; fusion dans `main` (tag patch) et `develop`.'],
    ], [2600, 2000, 5038], { firstBold: true, size: 17 }),
    spacer(80),
    P('**Branches `staging` / `prod`** : non recommandées. Les environnements sont des cibles de déploiement (dev ← `develop`, recette ← `release/*`, production ← tag sur `main`) ; des branches d’environnement dupliqueraient `main`/`release` et créeraient des divergences.'),
  ];
}

function s11() {
  return [
    H1('11. Performance'),
    P('Aucun test de charge n’a été réalisé : les éléments ci-dessous relèvent d’une **analyse statique**, complétée par les rares mesures réellement observées (taille des bundles, temps de démarrage).', { italics: false }),
    table(['Zone', 'Constat', 'Risque', 'Recommandation'], [
      ['Soldes `GET /api/wallets`', 'Appels HTTP synchrones à Formance à chaque requête, pagination interne, aucun cache', st('Moyen', C.orange), 'Cache court (5–10 s) ou projection des soldes ; timeouts déjà configurés (2 s / 5 s)'],
      ['Annuaire backoffice', 'Filtre KYC appliqué en mémoire sur jusqu’à 1 000 lignes (`CustomerDirectoryService.java:20, 72`)', st('Moyen à terme', C.orange), 'Jointure SQL via une vue exposée par le module KYC'],
      ['Historique', 'Pagination par curseur bornée 1–100', st('Faible', C.green), '—'],
      ['Outbox e-mails', '`FOR UPDATE SKIP LOCKED`, lots de 25 toutes les 5 s', st('Faible', C.green), 'Surveiller la file'],
      ['Limitation de débit', '`ConcurrentHashMap` en mémoire, éviction au-delà de 10 000 entrées', st('Faible', C.green), 'Redis pour le multi-instance'],
      ['Bundles', 'Backoffice 205,8 kB JS (63,8 kB gzip) ; mobile 2,8 Mo de bytecode Hermes (mesurés)', st('Faible', C.green), '—'],
      ['Démarrage / image', 'Backend démarré en 10,3 s ; JAR 67 Mo incluant `archunit`', st('Faible', C.green), 'Scope `test`, image JRE'],
      ['Observabilité', 'Pas de métriques exposées (`health`, `info` uniquement), 16 logs applicatifs', st('Moyen', C.orange), 'Micrometer/Prometheus, tableaux de bord p95'],
    ], [1900, 3500, 1300, 2938], { firstBold: true, size: 17 }),
  ];
}

function s12() {
  const W = [960, 3000, 1060, 2480, 2138];
  return [
    H1('12. Bugs et anomalies constatés'),
    table(['ID', 'Anomalie et localisation', 'Gravité', 'Impact et cause probable', 'Solution recommandée'], [
      ['BUG-01', '**Fonds insuffisants traités comme résultat inconnu.** Tout 4xx Formance devient `LedgerTransactionRejectedException` (`FormanceClient.java:852-883`), attrapé par le `catch (RuntimeException)` générique (`CustomerTransferIdempotencyService.java:268-301`). Vérifié par lecture (Formance absent).', sev.high, 'Statut UNKNOWN + 503 ; chaque nouvel essai avec la même clé renvoie 409 indéfiniment ; aucun outil ne peut clore une opération jamais postée.', 'Traduire le refus « fonds insuffisants » avant le passage en UNKNOWN : statut REJECTED, 422 + `errorCode`, comme déjà fait pour le FX (migration V17).'],
      ['BUG-02', '**`pageSize` invalide → 500.** `GET /api/transactions?pageSize=0` ou `1000` (`CustomerTransactionHistoryService.java:68-70`). Confirmé en mode dev et JAR.', sev.med, '`IllegalArgumentException` non traduite : erreur serveur au lieu d’une erreur client.', '`@Min(1) @Max(100)` sur le paramètre + gestionnaire global → 400.'],
      ['BUG-03', '**Ledger indisponible → 500 sur l’historique** alors que `/api/wallets` renvoie 503. Confirmé.', sev.med, '`LedgerProviderUnavailableException` traduite seulement dans `CustomerWalletService`.', 'Traduction globale en 503 + `errorCode`.'],
      ['BUG-04', '**Violation de frontière de module** : `OnboardingApiErrorHandler.java:16` référence `identity.api.AuthController`. `ModularityTest` échoue.', sev.med, 'Couplage non autorisé entre modules ; test d’architecture rouge.', 'Déplacer le gestionnaire d’`AuthController` dans `identity` ou cibler par package.'],
      ['BUG-05', '**`BackofficeMeSecurityTest` en erreur (12)** : contexte `@WebMvcTest` sans `IdentityRegistrationService`.', sev.med, 'Le test n’a pas suivi l’évolution du constructeur d’`AuthController`.', 'Ajouter `@MockitoBean IdentityRegistrationService`.'],
      ['BUG-06', '**Suite de tests non autonome** : sans `SUMSUB_*`, 254 tests sur 653 en erreur (« Sumsub secret key must not be blank », `SumsubRequestSigner.java:21`).', sev.med, 'Impossible de lancer les tests sur un poste neuf ou en CI sans secrets.', 'Valeurs factices dans `src/test/resources/application.properties`.'],
      ['BUG-07', '**`mvnw` non exécutable** (mode Git 100644) : « permission non accordée ».', sev.low, 'Bloque le build sous Linux/macOS et en CI.', '`git update-index --chmod=+x mvnw`.'],
      ['BUG-08', '**Profil `prod` incomplet** : sans issuer JWT, échec « JwtDecoder bean not found » ; variable absente de `.env.example`. Confirmé.', sev.low, 'Déploiement qui échoue sans documentation (comportement sûr).', 'Déclarer `issuer-uri` dans `application-prod.yml` et l’exemple d’environnement.'],
      ['BUG-09', '**Erreurs sans motif ni code** hors module KYC : p. ex. 400 sur `/api/fx/quotes` avec un corps vide de sens. Confirmé.', sev.low, 'Les clients ne peuvent pas distinguer les cas 409/503 (documenté dans `INTEGRATION_STATUS.md`).', '`ProblemDetail` + catalogue d’`errorCode` commun.'],
      ['BUG-10', '**Téléphone non validé** (`PersonalOnboardingRequest.java:9`). Confirmé.', sev.low, 'Données incohérentes, charge utile stockée.', 'Format E.164.'],
      ['BUG-11', '**Thème Keycloak NEOS non branché** : ni volume dans le compose, ni `loginTheme` dans le realm. Confirmé visuellement.', sev.low, 'Écrans d’authentification génériques.', 'Monter `./themes/neos` et définir `loginTheme`.'],
      ['BUG-12', '**Rôle CUSTOMER attribué à tout nouveau compte**, y compris staff créé par l’administrateur. Confirmé.', sev.low, 'Accès staff refusé tant que le rôle n’est pas retiré à la main ; erreurs d’exploitation.', 'Realm staff séparé (cf. SEC-04).'],
      ['BUG-13', '**Ports incohérents** : `.env.example` (backend 8082, Formance 8080), `mobile/.env.example` (API 8080), `CLAUDE.md` (backend 8080, Formance 8083).', sev.low, 'Mauvaise configuration lors de l’installation.', 'Aligner les fichiers d’exemple et le compose.'],
      ['BUG-14', '**Payments vide** : `SponsorBankPort` sans méthode.', sev.info, 'Code mort.', 'Implémenter ou supprimer.'],
      ['BUG-15', '**Caractères corrompus dans le backoffice démo** : « Mobile Â· Safari », « Incoming transfer Â· unknown reference » (`backoffice/src/demo/data/seed.ts:17, 44, 45`). Confirmé à l’écran (annexe A).', sev.low, 'Texte affiché incorrect ; fichier enregistré avec un mauvais encodage (point médian « · » converti deux fois).', 'Ré-encoder le fichier en UTF-8 et ajouter un contrôle d’encodage en CI.'],
    ], W, { size: 17 }),
  ];
}

function s13() {
  return [
    H1('13. Architecture actuelle vs architecture cible'),
    ...figure(`${S}/diagrams/arch-cible.png`, 'Architecture cible : même stack, consolidée et industrialisée (éléments nouveaux en vert).'),
    table(['Critère', 'Actuel', 'Cible recommandée', 'Effort'], [
      ['Technologies', 'Spring Boot 4.1, PostgreSQL, Keycloak, React, Expo', 'Identiques (montées de version mineures)', 'S'],
      ['Architecture', 'Monolithe modulaire, ledger monolithique, couplage Formance', 'Monolithe modulaire, ledger découpé, port LedgerProvider', 'L'],
      ['Sécurité', 'Realm permissif, profil dev par défaut, audience non vérifiée', 'Realm staff séparé + MFA, e-mail vérifié, profil obligatoire, audience vérifiée', 'S–M'],
      ['Erreurs / API', 'Corps Spring par défaut, codes partiels', 'ProblemDetail + `errorCode`, OpenAPI généré', 'M'],
      ['Maintenabilité', 'Bonne, mais pas de CI ni README', 'CI bloquante, README de démarrage, CODEOWNERS', 'M'],
      ['Performance', 'Non mesurée ; appels ledger synchrones', 'Cache court, métriques p95, tests de charge', 'M'],
      ['Scalabilité', 'Instance unique imposée', 'Redis partagé ; multi-instance possible', 'M'],
      ['Tests', 'Nombreux mais non autonomes', 'Autonomes, en CI, conteneurs activés', 'S'],
      ['Déploiement', 'Aucun (scripts Windows)', 'Images Docker, Compose + reverse proxy TLS, K8s plus tard', 'M'],
      ['Coût / complexité', '—', 'Évolution incrémentale, sans réécriture', '≈ 6–10 semaines'],
    ], [1700, 3200, 3700, 1038], { firstBold: true, size: 17 }),
    spacer(120),
    table(['Nature', 'Éléments'], [
      ['Corrections urgentes', 'SEC-01 à SEC-03, SEC-05, SEC-06, BUG-01'],
      ['Améliorations progressives', 'CI, gestion d’erreurs, dépendances, Docker/Compose, observabilité, limitation de débit partagée'],
      ['Refactoring', 'Découpage du module ledger, port LedgerProvider, dossier `backend/`, module payments'],
      ['Migration technologique', st('Aucune nécessaire', C.green)],
    ], [2600, 7038], { firstBold: true }),
  ];
}

function s14() {
  const W = [700, 3500, 1300, 2238, 800, 1100];
  const p = (t, c) => st(t, c);
  return [
    H1('14. Plan d’action priorisé'),
    table(['Prio.', 'Action (problème concerné)', 'Impact', 'Effet attendu', 'Effort', 'Dépend.'], [
      [p('P0', C.red), '**1.** Durcir le realm : force brute, politique de mot de passe, `verifyEmail`, plus d’inscription côté staff (SEC-01, SEC-02, SEC-04)', 'Sécurité', 'Comptes protégés, identité fiable', 'S', '—'],
      [p('P0', C.red), '**2.** Refuser `email_verified=false` à l’onboarding et à la recherche de destinataire (SEC-02)', 'Sécurité', 'Destinataires fiables', 'S', '1'],
      [p('P0', C.red), '**3.** Supprimer le profil `dev` par défaut et les mots de passe par défaut (SEC-03, SEC-07)', 'Sécurité', 'Pas de démarrage silencieux en dev', 'S', '—'],
      [p('P0', C.red), '**4.** Retirer `neos-dev-cli` des realms hors dev ; valider l’audience (SEC-05, SEC-06)', 'Sécurité', 'Jetons cantonnés à l’API', 'S–M', '1'],
      [p('P0', C.red), '**5.** Fonds insuffisants → 422 définitif (BUG-01)', 'Fonctionnel', 'Plus de clés bloquées', 'M', '—'],
      [p('P1', C.orange), '**6.** CI GitHub Actions (backend + PostgreSQL, mobile, backoffice, audits) + protection des branches', 'Qualité', 'Régressions bloquées', 'M', '7'],
      [p('P1', C.orange), '**7.** Tests autonomes, correction de `ModularityTest` et `BackofficeMeSecurityTest`, `chmod +x mvnw` (BUG-04 à 07)', 'Qualité', 'Suite verte reproductible', 'S', '—'],
      [p('P1', C.orange), '**8.** Gestion d’erreurs globale `ProblemDetail` + `errorCode` (BUG-02, 03, 09)', 'Robustesse', 'Plus de 500 évitables', 'M', '—'],
      [p('P1', C.orange), '**9.** Dépendances : pgjdbc 42.7.12, zod, node-forge ; plan Expo (SEC-08)', 'Sécurité', 'CVE connues corrigées', 'S', '—'],
      [p('P1', C.orange), '**10.** Assainir Git : `main` à jour, tag, suppression des branches fusionnées, `feat/kyc-backoffice`, purge des médias (SEC-12)', 'Organisation', 'Tronc lisible', 'S–M', '—'],
      [p('P2', C.blue), '**11.** Dockerfiles + compose complet (PostgreSQL, Keycloak, Formance, backend, backoffice, proxy TLS) + README', 'DevOps', 'Projet lançable en 1 commande', 'M', '6'],
      [p('P2', C.blue), '**12.** Observabilité : logs JSON, Prometheus, alertes ; limitation de débit partagée', 'Exploitation', 'Incidents détectés', 'M', '11'],
      [p('P2', C.blue), '**13.** Découper le module ledger, port LedgerProvider (refactoring)', 'Maintenabilité', 'Couplage réduit', 'L', '6'],
      [p('P2', C.blue), '**14.** Validation E.164, `archunit` en scope test, thème Keycloak (BUG-10, 11, SEC-13)', 'Qualité', 'Données propres', 'S', '—'],
      [p('P3', C.green), '**15.** Backoffice de conformité réel (revue KYC, blocage, transactions, rapprochement)', 'Fonctionnel', 'Remplace la démo', 'L', '1, 8'],
      [p('P3', C.green), '**16.** Phase 1 manquante : top-up, payout client, KYB, FR/AR', 'Fonctionnel', 'Périmètre complet', 'L', '—'],
      [p('P3', C.green), '**17.** Tests de charge (k6/Gatling) ; montées React 19, Vite, Expo SDK ; Kubernetes si nécessaire', 'Performance', 'Capacité mesurée', 'M–L', '11'],
    ], W, { size: 17 }),
    P('La colonne « Dépend. » indique le numéro de l’action à réaliser au préalable.', { size: 17, italics: true, color: C.muted }),
  ];
}

function s15() {
  return [
    H1('15. Roadmap de correction'),
    table(['Phase', 'Horizon indicatif', 'Contenu', 'Critère de sortie'], [
      [st('Phase 0 — Sécurisation', C.red), 'Semaine 1', 'Actions P0 : realm, profil, audience, BUG-01', 'Inscription avec mot de passe faible refusée ; e-mail vérifié requis ; 422 sur fonds insuffisants'],
      [st('Phase 1 — Fiabilisation', C.orange), 'Semaines 2–3', 'CI, tests autonomes, gestion d’erreurs, dépendances, hygiène Git', 'CI verte obligatoire sur `develop` ; 0 test en erreur ; 0 CVE élevée connue'],
      [st('Phase 2 — Industrialisation', C.blue), 'Semaines 4–6', 'Docker, compose complet, reverse proxy TLS, observabilité, recette déployée', 'Environnement de recette reproductible ; tableaux de bord disponibles'],
      [st('Phase 3 — Consolidation et périmètre', C.green), 'Semaines 7–12', 'Refactoring ledger, backoffice de conformité, fonctionnalités Phase 1, tests de charge', 'Parcours Phase 1 complets ; métriques de performance de référence'],
      [st('Phase 4 — Montée en charge', C.muted), 'Selon besoin', 'Kubernetes, autoscaling, montées de versions majeures', 'Décision basée sur les métriques de la phase 3'],
    ], [2300, 1500, 3200, 2638], { firstBold: false }),
  ];
}

function s16() {
  return [
    H1('16. Conclusion'),
    P('NEOS repose sur des fondations techniques saines : une stack moderne et pertinente, un monolithe modulaire bien découpé, une discipline de test et de documentation rare à ce stade, et des choix de sécurité applicative réfléchis. Le diagnostic n’appelle **ni réécriture ni changement de technologie** : NestJS et MongoDB n’apporteraient rien qui justifie le risque d’une migration, et React/React Native avec Expo sont déjà en place.'),
    P('Les écarts se concentrent sur trois points : une **configuration d’identité trop permissive** (corrigeable en quelques jours), quelques **défauts de robustesse** sur les flux financiers (fonds insuffisants, erreurs 500), et l’**absence d’industrialisation** (Docker, CI/CD, supervision, Git). En suivant la roadmap — sécurisation la première semaine, fiabilisation puis industrialisation sur six semaines — le projet peut atteindre un niveau de recette crédible, avant de compléter le périmètre fonctionnel Phase 1.'),
    callout('Décision recommandée', [
      'Geler les nouvelles fonctionnalités pendant la Phase 0 et engager immédiatement les actions P0.',
      'Conserver la stack actuelle ; investir dans la CI et la conteneurisation avant d’élargir l’équipe ou le périmètre.',
    ]),
  ];
}

function annexes() {
  return [
    H1('Annexe A — Captures d’écran de l’application', { pageBreakBefore: true }),
    P('Toutes les captures proviennent de l’exécution réelle du code du dépôt pendant l’audit, dans une copie isolée du projet. Aucune image n’a été retouchée.', { size: 19 }),
    H2('D.1 Application mobile (React Native / Expo)'),
    P('Rendu dans Chrome via React Native Web (fenêtre 390 × 844), en mode développement avec toutes les fonctions simulées (`EXPO_PUBLIC_MOCK_FEATURES=all`), d’où le bandeau « MOCK ». Pour ce rendu web uniquement, le stockage sécurisé natif (`expo-secure-store`, indisponible dans un navigateur) a été remplacé par un stockage en mémoire dans la copie de test. Les écrans M14 à M16 et M11 affichent « Not available yet » : en mode démo, le dépôt, le virement, la conversion et l’historique exigent le backend réel. Le rendu Android natif peut différer légèrement.', { size: 18, keepNext: true }),
    gallery([
      ['M1', 'm01-welcome', 'Bienvenue'], ['M2', 'm03-create-account', 'Création du compte'], ['M3', 'm04-onboarding', 'Informations essentielles'],
      ['M4', 'm05-review', 'Vérification et acceptation des CGU'], ['M5', 'm06-kyc', 'Vérification d’identité (Sumsub)'], ['M6', 'm09-verified', 'Identité vérifiée (simulée)'],
      ['M7', 'm10-account-ready', 'Compte activé'], ['M8', 'm11-home', 'Accueil : portefeuilles 6 devises'], ['M9', 'm15-details', 'Coordonnées du compte'],
      ['M10', 'm16-notifications', 'Notifications'], ['M11', 'm17-activity', 'Historique (non disponible en démo)'], ['M12', 'm18-profile', 'Profil'],
      ['M13', 'm19-security', 'Sécurité du compte'], ['M14', 'm12-add-money', 'Ajouter de l’argent (non disponible)'], ['M15', 'm13-send', 'Envoyer (non disponible en démo)'],
      ['M16', 'm14-convert', 'Convertir (non disponible en démo)'],
    ].map(([label, n, caption], i) => ({ label, caption, path: `${S}/fig/gallery/M${String(i + 1).padStart(2, '0')}-${n}.png` })), 4, 150),
    H2('D.2 Backoffice connecté et Keycloak (exécution réelle)'),
    P('Backoffice React relié au backend Spring Boot et à Keycloak 26.7.2 (realm du dépôt), sans ledger Formance.', { size: 18, keepNext: true }),
    gallery([
      ['C1', 'shots/bo-connected-start.png', 'Page de connexion staff'], ['C2', 'shots/kc-staff-login-with-register-link.png', 'Keycloak : connexion avec lien « Register »'],
      ['C3', 'shots/kc-register-weak-password-form.png', 'Keycloak : inscription avec le mot de passe « 1 »'], ['C4', 'shots/kc-register-weak-password-result.png', 'Accès refusé au compte client'],
      ['C5', 'shots/bo-staff-home.png', 'Accueil staff'], ['C6', 'shots/bo-staff-customers.png', 'Annuaire clients'],
      ['C7', 'shots/bo-staff-customer-detail.png', 'Fiche client (ledger indisponible)'],
    ].map(([label, p, caption]) => ({ label, caption, path: `${S}/${p}` })), 2, 300),
    H2('D.3 Backoffice — version démo (données simulées)'),
    P('Écrans du mode démo (`vite --mode demo`), exclus du build de production. Les actions sont marquées « simulated » et ne modifient que la session du navigateur.', { size: 18, keepNext: true }),
    gallery([
      ['S1', 'd01-login', 'Connexion (démo)'], ['S2', 'd02-overview', 'Vue d’ensemble'], ['S3', 'd03-users', 'Utilisateurs'],
      ['S4', 'd04-user-detail', 'Fiche utilisateur (texte « Â· » corrompu)'], ['S5', 'd05-user-suspend-dialog', 'Suspension (action simulée)'],
      ['S6', 'd06-kyc-queue', 'File de revue KYC'], ['S7', 'd07-kyc-case', 'Dossier KYC'], ['S8', 'd08-transactions', 'Transactions'],
      ['S9', 'd09-transaction-detail', 'Détail d’une transaction'], ['S10', 'd10-reconciliation', 'Rapprochement'], ['S11', 'd11-alerts', 'Alertes'],
    ].map(([label, n, caption]) => ({ label, caption, path: `${S}/shots/demo/${n}.png` })), 2, 300),
  ];
}

module.exports = { s8, s9, s10, s11, s12, s13, s14, s15, s16, annexes };
