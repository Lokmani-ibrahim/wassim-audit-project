// Sections 1 à 7 du rapport
const L = require('./lib');
const { d, C, P, H1, H2, H3, bullets, table, callout, code, figure, spacer, sev, st, TR } = L;
const S = process.env.SCRATCH;

function cover() {
  const { Paragraph, TextRun, AlignmentType, BorderStyle, PageBreak } = d;
  return [
    new Paragraph({ children: [], spacing: { before: 300, after: 0 } }),
    new Paragraph({ children: [TR({ text: 'RAPPORT D’AUDIT TECHNIQUE', bold: true, size: 44, color: C.navy })], spacing: { after: 120 } }),
    new Paragraph({ children: [TR({ text: 'Projet NEOS — Application fintech multi-devises', size: 28, color: C.navy })], spacing: { after: 240 },
      border: { bottom: { style: BorderStyle.SINGLE, size: 6, color: C.gold, space: 8 } } }),
    spacer(120),
    table(['Élément', 'Détail'], [
      ['Objet', 'Audit global : sécurité, architecture, fonctionnel, technologies, Docker et infrastructure, Git, performance, bugs ; architecture cible et plan d’action priorisé.'],
      ['Périmètre', 'Backend Java/Spring Boot (racine du dépôt), application mobile React Native/Expo (`mobile/`), backoffice React (`backoffice/`), configuration Keycloak (`infra/`), documentation (`docs/`).'],
      ['Date', '23 novembre 2026'],
      ['Émis par', 'UFUK Connect'],
      ['Méthode', 'Lecture du code et de la configuration ; exécution réelle dans un environnement Linux isolé (compilation, 3 suites de tests, démarrage du backend, de Keycloak et du backoffice, appels API, navigateur headless, scan de dépendances). Aucune modification du code source.'],
      ['Convention', '« Confirmé » = observé à l’exécution ou vérifié ligne à ligne dans le code. « Potentiel » = risque déduit de la configuration, non exploité. Les métriques non mesurées sont signalées comme telles.'],
    ], [2200, 7438], { firstBold: true }),
    spacer(100),
    table(['Indicateur clé', 'Valeur mesurée pendant l’audit'], [
      ['Compilation', 'Backend 208 fichiers : réussie · Mobile `tsc` : 0 erreur · Backoffice `tsc -b` + build : réussis'],
      ['Tests automatisés', 'Backend 573 réussis / 653 (13 en erreur, 67 ignorés) · Mobile 233/233 · Backoffice 33/33'],
      ['Sécurité (13 constats)', '3 élevés, 5 moyens, 4 faibles, 1 informatif — aucune vulnérabilité critique'],
      ['Dépendances vulnérables', 'Mobile 13 (2 élevées, 11 modérées) · Backoffice 0 en production · Backend 1 élevée (pgjdbc 42.7.11)'],
      ['Industrialisation', '0 Dockerfile · 0 pipeline CI · compose limité à Keycloak'],
    ], [2600, 7038], { firstBold: true }),
  ];
}

function toc() {
  const { TableOfContents, Paragraph, TextRun, PageBreak } = d;
  return [
    new Paragraph({ pageBreakBefore: true, children: [TR({ text: 'Sommaire', bold: true, size: 30, color: C.navy })], spacing: { after: 200 },
      border: { bottom: { style: d.BorderStyle.SINGLE, size: 6, color: C.gold, space: 2 } } }),
    new TableOfContents('Sommaire', { hyperlink: true, headingStyleRange: '1-2' }),
  ];
}

function s1() {
  return [
    H1('1. Résumé exécutif', { pageBreakBefore: true }),
    P('NEOS est une application fintech multi-devises (modèle « sponsor bank ») composée d’un backend Java/Spring Boot organisé en monolithe modulaire, d’une application mobile Android en React Native/Expo et d’un backoffice web React pour le personnel. Le code est d’un niveau nettement supérieur à la moyenne : typage strict, tests nombreux, règles métier défensives (idempotence des virements, montants en unités entières, anti-énumération). Le projet n’est cependant **pas prêt pour la production** : la configuration d’identité (Keycloak) est permissive, plusieurs parcours Phase 1 manquent, et aucune chaîne d’industrialisation (Docker, CI/CD, supervision) n’existe.'),
    table(['Axe', 'Verdict', 'Commentaire court'], [
      ['Validité du code', st('Valide avec réserves', C.orange), 'Tout compile. Mobile 233/233, backoffice 33/33. Backend : 573 réussis sur 653, 67 ignorés, 13 en erreur (2 classes) — et 254 en erreur si les variables Sumsub ne sont pas fournies.'],
      ['Sécurité', st('À renforcer — 6/10', C.orange), 'Bonne base applicative (OIDC/PKCE, rôles sur chaque endpoint, webhook HMAC, idempotence). Faiblesses confirmées : inscription libre sans vérification d’e-mail, aucune politique de mot de passe ni anti-force brute, profil « dev » actif par défaut.'],
      ['Architecture', st('Solide — 7/10', C.green), 'Monolithe modulaire cohérent (Spring Modulith). Module ledger ≈ 60 % du code, 8 services couplés directement au client Formance, 1 violation de frontière détectée par les tests.'],
      ['Fonctionnel', st('Partiel', C.orange), 'Inscription, onboarding, KYC, portefeuilles, virement interne, historique, notifications et annuaire backoffice présents. Absents : dépôt réel, virement externe côté client, KYB, FR/AR, écrans de conformité du backoffice.'],
      ['Technologies', st('Pertinentes — 8/10', C.green), 'Stack récente et adaptée (Spring Boot 4.1, Java 21, PostgreSQL, Keycloak 26.7, Expo SDK 55). Aucune migration de stack n’est justifiée ; seules des montées de version sont à planifier.'],
      ['Docker / infra', st('Insuffisant — 3/10', C.red), 'Aucun Dockerfile, compose limité à Keycloak (identifiants en clair), pas de reverse proxy, pas de CI/CD, outillage Windows uniquement.'],
      ['Git', st('À assainir — 4/10', C.red), '46 branches actives lisibles, « main » jamais mise à jour (integration +125 commits), aucune CI ni tag. Le dépôt audité est un instantané de 3 commits contenant une vidéo de 57 Mo.'],
      ['Performance', st('Non mesurée', C.muted), 'Analyse statique : pagination bornée et outbox efficace, mais appels Formance synchrones sans cache et filtrage KYC en mémoire dans le backoffice.'],
    ], [1900, 1900, 5838], { firstBold: true }),
    spacer(160),
    callout('Risques prioritaires (P0)', [
      '**Identité** : n’importe qui peut créer un compte (y compris depuis la page de connexion staff) avec le mot de passe « 1 », sans vérification d’e-mail ; or le backend identifie les destinataires de virements par cet e-mail non vérifié.',
      '**Force brute** : aucune détection de force brute ni politique de mot de passe dans le realm Keycloak.',
      '**Configuration** : un serveur démarré sans `SPRING_PROFILES_ACTIVE` tourne en profil « dev » (virements activés, mot de passe de base par défaut) — confirmé avec le JAR de production.',
      '**Virements** : un refus « fonds insuffisants » du ledger est enregistré comme résultat inconnu (503) et bloque définitivement la clé d’idempotence.',
      '**Qualité** : aucune CI — deux classes de tests cassées ont été fusionnées sans être détectées.',
    ], 'red'),
  ];
}

function s2() {
  return [
    H1('2. Objectifs, périmètre et méthode'),
    P('L’audit vise à établir un état des lieux objectif du projet, à qualifier les risques et à proposer une trajectoire réaliste. Toutes les conclusions proviennent du dépôt audité et de son exécution ; le rapport de référence n’a servi que de modèle de présentation.'),
    table(['Étape', 'Ce qui a été fait réellement'], [
      ['Inspection', 'Lecture de la structure (583 fichiers suivis), des 3 `package.json`, du `pom.xml`, des profils Spring, du realm Keycloak, de la documentation `docs/` et de l’historique Git local.'],
      ['Exécution', 'Copie du projet dans un répertoire isolé ; `mvnw compile/test/package` (Java 21, PostgreSQL 16 éphémère), `npm ci` + contrôles mobile et backoffice, démarrage réel du backend (mode Maven puis JAR), de Keycloak 26.7.2 avec import du realm, et du backoffice (modes connecté et démo) ; rendu de l’application mobile dans un navigateur en mode démo (annexe A).'],
      ['Tests fonctionnels', 'Inscription Keycloak, connexion staff, 30 appels API authentifiés et non authentifiés, limitation de débit, profil `prod`, navigation backoffice avec navigateur headless (captures).'],
      ['Sécurité', 'Revue de `SecurityConfig`, `StaffAccessPolicy`, contrôleurs et DTO ; recherche de secrets ; `npm audit` ; Trivy sur le `pom.xml`.'],
      ['Git', 'Historique local + analyse des 3 captures GitHub « Active branches » fournies.'],
    ], [1900, 7738], { firstBold: true }),
    spacer(100),
    P('**Échelle de gravité** : Critique (exploitation directe à fort impact) · Élevée · Moyenne · Faible · Info. **Effort** : S (≤ 1 jour), M (2 à 5 jours), L (1 à 3 semaines), estimations indicatives pour un développeur expérimenté.', { size: 19 }),
  ];
}

function s3() {
  return [
    H1('3. Présentation du projet et stack actuelle'),
    table(['Couche', 'Technologie (version constatée)', 'Rôle'], [
      ['Application mobile', 'React Native 0.83.10, Expo SDK 55, React 19.2, TypeScript 5.9 strict', 'Parcours client Android (iOS configuré, non ciblé)'],
      ['Backoffice web', 'React 18.3, Vite 6.4, keycloak-js 26.2, Vitest 4', 'Outil interne du personnel (lecture seule) + version démo'],
      ['Identité', 'Keycloak 26.7.2 (OIDC, PKCE S256, clients publics)', 'Authentification clients et staff, rôles de realm'],
      ['Backend', 'Java 21, Spring Boot 4.1.0, Spring Modulith 2.1, Spring Security 7', 'API REST, règles métier, ordonnanceurs'],
      ['Persistance', 'PostgreSQL + Flyway (18 migrations), JPA (5 dépôts) + JdbcTemplate (14 classes)', 'Identité, onboarding, KYC, outbox, idempotence, audit'],
      ['Grand livre', 'Formance Ledger (HTTP, schémas v1.0.0 / v1.1.0)', 'Source de vérité des soldes et mouvements'],
      ['KYC', 'Sumsub (SDK mobile 1.46.1 patché, API signée, webhook HMAC)', 'Vérification d’identité'],
      ['E-mail', 'Microsoft Graph via outbox + dispatcher', 'Notifications (désactivées par défaut)'],
    ], [1800, 4400, 3438], { firstBold: true }),
    spacer(120),
    H2('3.1 Volumétrie mesurée'),
    table(['Composant', 'Code', 'Tests', 'Observation'], [
      ['Backend', '208 fichiers · 21 682 lignes', '131 fichiers · 35 646 lignes', 'Ratio tests/code ≈ 1,6'],
      ['Mobile', '97 fichiers · 12 505 lignes', '22 fichiers · 4 218 lignes', 'Aucun `any`, lint personnalisé'],
      ['Backoffice', '12 fichiers · 916 lignes', '5 fichiers', 'Version connectée minimale'],
      ['Documentation', '24 fichiers Markdown · 5 589 lignes', '—', 'Contrat d’API, audits, journal d’ingénierie'],
    ], [1700, 2900, 2600, 2438], { firstBold: true }),
    spacer(120),
    H2('3.2 Organisation du dépôt'),
    table(['Dossier', 'Contenu', 'Fichiers'], [
      ['`src/`', 'Backend : `main/java/com/fintech/<module>`, `resources` (profils, migrations), `test/`', '361'],
      ['`mobile/`', 'Application Expo : `src/` (api, services, screens, money, transfers, kyc, lock, fx…), `tests/`, `plugins/`, `patches/`, `scripts/`', '145'],
      ['`backoffice/`', 'React/Vite : `auth`, `customers`, `demo`, scripts de vérification', '33'],
      ['`docs/`', 'Contrat d’API mobile, statut d’intégration, audits, plans et rapports de test', '24'],
      ['`infra/`', 'Keycloak (compose, realm, thème), schémas Formance', '7'],
      ['`ops/`, `scripts/`', 'Durcissement PostgreSQL (psql), démarrage local PowerShell', '3'],
      ['Racine', '`pom.xml`, `mvnw`, `.env.example`, `CLAUDE.md`, `README.md` (2 lignes), `neos-fx-demo.ps1`', '10'],
    ], [1500, 7238, 900], { firstBold: true }),
  ];
}

function s4() {
  return [
    H1('4. Architecture et structure'),
    ...figure(`${S}/diagrams/arch-actuelle.png`, 'Architecture actuelle reconstituée à partir du code et de la configuration (volumes mesurés).'),
    H2('4.1 Modules du backend'),
    table(['Module', 'Fichiers', 'Lignes', 'Responsabilité', 'Remarque'], [
      ['ledger', '96', '12 921', 'Portefeuilles, virements, FX, payouts, rapprochement, provisioning', st('Trop volumineux (≈ 60 %)', C.orange)],
      ['kyc', '31', '3 036', 'Sumsub, jeton SDK, webhook, états KYC', 'Bien isolé derrière un port'],
      ['notifications', '29', '2 997', 'Outbox, envoi Graph, flux in-app', '`SKIP LOCKED`, idempotent'],
      ['identity', '26', '1 594', 'Utilisateurs, sécurité, rôles, accès staff', 'Contient `SecurityConfig`'],
      ['onboarding', '15', '634', 'Onboarding personnel, profil client', st('Viole une frontière (cf. BUG-04)', C.orange)],
      ['backoffice', '7', '400', 'Lectures staff : annuaire, soldes, historique', 'Lecture seule'],
      ['shared', '2', '82', 'Monnaie, politiques communes', '—'],
      ['payments', '1', '3', 'Port `SponsorBankPort` vide', st('Non implémenté', C.red)],
    ], [1400, 900, 900, 3638, 2800], { firstBold: true }),
    spacer(120),
    H2('4.2 Points forts'),
    ...bullets([
      'Monolithe modulaire avec frontières vérifiées par des tests d’architecture (Spring Modulith, ArchUnit) : bon compromis pour une équipe réduite.',
      'Ports et adaptateurs pour Sumsub, Microsoft Graph et le sponsor bank ; règles métier défensives et abondamment commentées.',
      'Mobile en couches claires (`api/`, `services/`, `money/`, `transfers/`, `kyc/`, `lock/`, `screens/`) ; moteurs de virement et de FX purs et testés ; mocks isolés dans un registre et exclus des builds de production (contrôle `check:release-bundle` réussi).',
      'Backoffice : configuration validée au démarrage (HTTPS hors localhost, API de même origine), version démo exclue du build de production (contrôle réussi).',
    ]),
    H2('4.3 Faiblesses structurelles'),
    ...bullets([
      '**Module ledger surdimensionné** : il mélange cinq sous-domaines (wallets, transfers, FX, payouts, reconciliation). Le découper réduirait le couplage et la taille des revues.',
      '**Couplage direct au fournisseur** : 8 classes de service (`CustomerWalletService`, `CustomerTransactionHistoryService`, `WalletProvisioningService`, services de rapprochement, FX démo…) importent `adapter.formance.*` au lieu de passer par le port : remplacer ou simuler Formance est coûteux.',
      '**Gestion d’erreurs dispersée** : 4 `@RestControllerAdvice` limités à certains contrôleurs ; les autres modules renvoient le corps d’erreur Spring par défaut (sans code ni message) — cause directe de BUG-02, BUG-03 et BUG-09.',
      '**Backend à la racine du monodépôt** : `pom.xml`, `mvnw` et `src/` sont mélangés aux dossiers des autres applications ; un dossier `backend/` serait plus lisible.',
      '**Outillage local Windows uniquement** (`scripts/run-backend-local.ps1` avec un `JAVA_HOME` codé en dur, `mobile/scripts/android.ps1`) et README racine vide : l’accueil d’un nouveau développeur dépend de la documentation interne.',
    ]),
  ];
}

function s5() {
  const W1 = [930, 1060, 3648, 1200, 2800];
  return [
    H1('5. Audit de sécurité'),
    H2('5.1 Points forts vérifiés'),
    ...bullets([
      'Authentification OIDC avec PKCE S256 pour le mobile et le backoffice, clients publics sans secret ; jetons mobiles en `SecureStore`, jetons backoffice en mémoire.',
      'Chaque endpoint client porte `@PreAuthorize("hasRole(\'CUSTOMER\')")` ; les routes backoffice passent par `StaffAccessPolicy` (issuer, audience, `azp`, rôle) avec refus par défaut — **confirmé** : 403 pour un client et pour un staff portant aussi le rôle CUSTOMER.',
      'API sans état ; en-têtes observés : `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Cache-Control: no-store`. CSRF désactivé, ce qui est correct pour une API Bearer.',
      'Webhook Sumsub vérifié par HMAC sur le corps brut, désactivé par défaut. Requêtes SQL paramétrées (aucune concaténation trouvée).',
      'Virements : clé d’idempotence portée par l’expéditeur authentifié, aucune relance automatique d’un résultat inconnu. Recherche de destinataire : réponse 404 identique (anti-énumération), e-mail masqué, limitation à 10/min — **confirmé** (429 à la 11ᵉ requête).',
      'Garde-fous de production — **confirmés** : le profil `prod` refuse de démarrer sans issuer JWT, puis en mode Sumsub sandbox.',
      'Aucun fichier `.env` ni clé privée suivi par Git ; aucun secret réel trouvé dans le code.',
    ]),
    H2('5.2 Vulnérabilités et risques'),
    P('Aucune vulnérabilité de niveau Critique n’a été confirmée.', { size: 19, italics: true }),
    table(['ID', 'Gravité', 'Constat et preuve', 'Statut', 'Recommandation'], [
      ['SEC-01', sev.high, '**Aucune protection contre la force brute ni politique de mot de passe** : `bruteForceProtected: false` (`neos-realm.json:39`), aucune `passwordPolicy`. Compte créé avec le mot de passe « 1 » (figure 2).', st('Confirmé', C.red), 'Activer la détection de force brute et une politique (≥ 12 caractères, `notUsername`, historique) ; MFA pour le staff.'],
      ['SEC-02', sev.high, '**E-mail non vérifié utilisé comme identifiant de destinataire** : inscription ouverte (`registrationAllowed: true`, `verifyEmail: false`, l. 31 et 34) ; le backend lit le claim `email` sans contrôler `email_verified` (`AuthController.java:30`, `PersonalOnboardingController.java:32`) et cherche les destinataires par e-mail (`RecipientLookupService.java:88`). Jeton obtenu avec `email_verified=false`. Scénario : s’inscrire avec l’adresse d’un tiers non encore client pour recevoir ses virements (après KYC).', st('Confirmé (config.) / Potentiel (exploitation)', C.orange), '`verifyEmail: true` ; refuser l’onboarding et la recherche si `email_verified` est faux ; afficher le nom vérifié KYC avant confirmation du virement.'],
      ['SEC-03', sev.high, '**Profil « dev » actif par défaut** : `${SPRING_PROFILES_ACTIVE:dev}` (`application.yaml:17`). Le JAR de production lancé sans variable démarre en dev : API de virement activée (`application-dev.yml:33`), mot de passe de base par défaut `123456789` (l. 22), Flyway actif.', st('Confirmé', C.red), 'Supprimer la valeur par défaut et échouer au démarrage si le profil manque.'],
      ['SEC-04', sev.med, '**Staff et clients dans le même realm** : la page de connexion du backoffice propose « Register » ; tout compte reçoit CUSTOMER par défaut, y compris un staff créé par l’administrateur, qui est refusé tant que le rôle n’est pas retiré à la main.', st('Confirmé', C.red), 'Realm (ou flux d’authentification) dédié au staff, sans inscription, avec MFA obligatoire.'],
      ['SEC-05', sev.med, '**Client de développement dans l’export du realm** : `neos-dev-cli`, public, flux « mot de passe direct » activé, `redirectUris` et `webOrigins` = `/*` (l. 876-897). Jeton obtenu par `grant_type=password` pendant l’audit.', st('Confirmé', C.red), 'Retirer ce client de tout realm hors poste de développement ; un fichier de realm par environnement.'],
      ['SEC-06', sev.med, '**Audience non contrôlée sur les endpoints client** : seul l’issuer est validé (`SecurityConfig.java:69-75`). Un jeton émis pour `neos-dev-cli` (`aud=account`) est accepté par `/api/auth/me` (200).', st('Confirmé', C.red), 'Valider `aud=neos-api` (mapper d’audience sur le client mobile + `JwtValidators`).'],
      ['SEC-07', sev.med, '**Identifiants par défaut en clair** : `admin/admin` et `keycloak_dev_password` (`infra/keycloak/compose.yml:10, 32-33, 39`) ; port `8081` publié sur toutes les interfaces (l. 43).', st('Confirmé', C.red), 'Variables `.env` aléatoires, liaison sur `127.0.0.1`.'],
      ['SEC-08', sev.med, '**Dépendances vulnérables** : mobile 13 en production (2 élevées : `zod` < 4.6.6, `@expo/cli` ; 11 modérées : `node-forge`, `glob`, `minimatch`, chaîne `@expo/config`) — surtout dans l’outillage de build ; backend : `org.postgresql:postgresql` 42.7.11, CVE-2026-54291 (élevée, contournement de la protection MITM SCRAM, corrigée en 42.7.12).', st('Confirmé (scanners)', C.red), '`npm audit fix` sans `--force`, forcer pgjdbc 42.7.12, planifier la montée Expo.'],
      ['SEC-09', sev.low, '**Validation d’entrée faible** : `phoneNumber` n’a qu’une taille max (`PersonalOnboardingRequest.java:9`) ; `<script>alert(1)</script>` a été accepté, stocké et renvoyé par `/api/profile`. React/React Native échappent le rendu : non exploitable en l’état.', st('Confirmé (stockage)', C.orange), 'Valider au format E.164 ; encoder à la sortie dans les e-mails et reçus PDF.'],
      ['SEC-10', sev.low, '**Limitation de débit partielle et locale** : seule la recherche de destinataire est limitée, en mémoire, par instance (`RecipientLookupRateLimiter.java:16-20`).', st('Confirmé', C.red), 'Limitation globale au reverse proxy + compteurs partagés (Redis/Bucket4j).'],
      ['SEC-11', sev.low, '**Stack traces dans les réponses d’erreur** en exécution `mvn spring-boot:run` (devtools) ; absentes du JAR packagé (vérifié).', st('Confirmé (dev)', C.orange), 'Fixer `server.error.include-stacktrace=never` explicitement.'],
      ['SEC-12', sev.low, '**Fichiers sensibles versionnés** : `mobile/native-build-diagnostic.txt` (nom d’utilisateur et chemins Windows) ; vidéo `Screen_recording_…webm` de 57 Mo non relue, non référencée par le code.', st('Confirmé', C.red), 'Supprimer, purger l’historique (`git filter-repo`), Git LFS pour les médias.'],
      ['SEC-13', sev.info, 'Sessions : SSO inactif 30 min, session hors ligne 30 jours, `revokeRefreshToken: false` ; `archunit` (bibliothèque de test) embarqué dans le JAR de production (`pom.xml:182-185`, vérifié dans le JAR).', st('Confirmé', C.muted), 'Réduire les durées, activer la rotation des refresh tokens ; scope `test`.'],
    ], W1, { size: 17 }),
    spacer(100),
    ...figure(`${S}/fig/fig-inscription-libre.png`, '(a) page de connexion du backoffice staff avec le lien « Register » ; (b) inscription acceptée avec le mot de passe « 1 » ; (c) le compte est créé et connecté, puis correctement refusé par le backoffice (403).'),
    callout('Recommandation', [
      'Traiter SEC-01 à SEC-03 avant toute ouverture à des utilisateurs réels : ce sont des changements de configuration (realm + profil Spring) à faible coût (effort S) et à fort impact.',
      'Versionner un realm « production » distinct, sans client de développement, et le valider automatiquement en CI (script qui vérifie `bruteForceProtected`, `verifyEmail`, `passwordPolicy`).',
    ]),
  ];
}

function s6() {
  const W2 = [2500, 1500, 3138, 2500];
  const ok = st('Fonctionnel', C.green), part = st('Partiellement fonctionnel', C.orange), ko = st('Défaillant', C.red),
    ni = st('Non implémenté', C.red), nv = st('Non vérifié', C.muted);
  return [
    H1('6. Audit fonctionnel'),
    P('Les états ci-dessous distinguent ce qui a été **exécuté** (API, Keycloak, backoffice) de ce qui n’a pu être que lu (application mobile, intégrations externes). Formance, Sumsub réel et Microsoft Graph n’étaient pas disponibles dans l’environnement d’audit.'),
    table(['Fonctionnalité', 'État', 'Anomalie / observation', 'Recommandation'], [
      ['Inscription et connexion (Keycloak)', ok, 'Testé : inscription sans vérification d’e-mail, mot de passe « 1 » accepté', 'SEC-01, SEC-02'],
      ['Onboarding personnel `POST /api/onboarding/personal`', part, 'Testé : 200 ; téléphone non validé', 'Validation E.164'],
      ['Profil `GET /api/profile`, `GET /api/auth/me`', ok, 'Testé : 200 ; 404 explicite avant onboarding', '—'],
      ['KYC Sumsub (start, jeton SDK, webhook)', nv, '502 `SUMSUB_UPSTREAM_ERROR` propre avec clés factices ; webhook non testé (Sumsub réel requis)', 'Recette avec le sandbox Sumsub'],
      ['Portefeuilles 6 devises `GET /api/wallets`', nv, 'Ledger absent : 503 propre « temporarily unavailable »', 'Fournir Formance en compose'],
      ['Historique `GET /api/transactions`', ko, 'Testé : **500** si ledger indisponible et si `pageSize` = 0 ou 1000', 'BUG-02, BUG-03'],
      ['Recherche de destinataire', ok, 'Testé : 403 expéditeur non éligible, 429 après 10 appels', 'Compteurs partagés'],
      ['Virement interne', part, 'Refus correct d’un expéditeur non éligible (403) ; « fonds insuffisants » → 503/UNKNOWN (lecture du code)', 'BUG-01'],
      ['Conversion FX', part, 'Devis : endpoint actif, 400 sans motif ; conversions démo désactivées par défaut (404) ; écran mobile « sandbox only »', 'Codes d’erreur, flux réel'],
      ['Dépôt (top-up)', ni, 'Écran d’aperçu sandbox (instructions d’exemple), aucun crédit réel', 'Phase 1'],
      ['Virement externe / payout', ni, 'Logique backend présente, aucun endpoint client', 'Phase 1'],
      ['Notifications in-app `GET /api/notifications`', ok, 'Testé : 200, liste vide paginée', '—'],
      ['E-mails (Microsoft Graph)', nv, 'Désactivé par défaut (choix volontaire)', 'Allowlist hors prod'],
      ['Reçus PDF, verrou biométrique (mobile)', nv, 'Implémentés et couverts par tests unitaires ; non exécutés sur appareil', 'Test sur appareil'],
      ['Langues FR/AR (RTL)', ni, 'Un seul dictionnaire `en.ts`', 'i18n'],
      ['KYB (comptes entreprise), support client', ni, 'Aucune référence dans le code', 'Phase 1'],
      ['Backoffice : connexion staff et contrôle d’accès', ok, 'Testé : client refusé, staff COMPLIANCE_OFFICER accepté', 'SEC-04'],
      ['Backoffice : annuaire et fiche client', ok, 'Testé ; dégradation propre si ledger indisponible (« Retry »)', '—'],
      ['Backoffice : revue KYC, blocage, transactions, rapprochement, alertes', ni, 'Uniquement en version démo, actions « simulated »', 'À construire sur l’API'],
      ['Thème Keycloak NEOS', ko, 'Présent dans `infra/` mais non monté ni référencé : écran Keycloak par défaut (figure 2a)', 'Monter le thème'],
    ], W2, { size: 17 }),
    spacer(100),
    ...figure(`${S}/fig/fig-bo-annuaire.png`, 'Backoffice connecté (exécution réelle) : annuaire client en lecture seule, seul écran métier disponible avec l’accueil.', 600),
    ...figure(`${S}/fig/fig-bo-fiche.png`, 'Fiche client : soldes et historique indisponibles sans ledger, message clair et bouton « Retry ».', 600),
    ...figure(`${S}/fig/fig-bo-demo-kyc.png`, 'Version démo du backoffice : la revue KYC, les transactions, le rapprochement et les alertes n’existent qu’en simulation.', 600),
  ];
}

function s7() {
  return [
    H1('7. Validité du code et tests'),
    table(['Composant', 'Contrôle exécuté', 'Résultat'], [
      ['Backend', '`sh mvnw -B -DskipTests compile`', st('Réussi — 208 fichiers', C.green)],
      ['Backend', '`./mvnw` (exécution directe)', st('Échec : « permission non accordée » (mode Git 100644)', C.red)],
      ['Backend', '`mvnw test` sans variables Sumsub', st('653 tests : 254 erreurs, 66 ignorés', C.red)],
      ['Backend', '`mvnw test` avec Sumsub factice', st('573 réussis, 67 ignorés, 13 erreurs', C.orange)],
      ['Backend', '`ModularityTest`', st('Échec : onboarding → `identity.api.AuthController`', C.red)],
      ['Backend', '`BackofficeMeSecurityTest` (12 tests)', st('Échec : bean `IdentityRegistrationService` absent', C.red)],
      ['Backend', '`mvnw package` + `java -jar`', st('Réussi — JAR 67 Mo, démarrage en 10,3 s', C.green)],
      ['Mobile', '`npx tsc --noEmit` · `npm run lint`', st('0 erreur · 0 problème', C.green)],
      ['Mobile', '`npm test`', st('233 / 233 réussis', C.green)],
      ['Mobile', '`check:release-bundle` · `check:release-readiness`', st('0 mock dans le bundle · 6/6 fonctions réelles', C.green)],
      ['Mobile', '`npm audit --omit=dev`', st('13 vulnérabilités (2 élevées)', C.orange)],
      ['Backoffice', '`tsc -b` · `npm test` · `npm run build`', st('Réussi · 33 / 33 · réussi (205,8 kB JS)', C.green)],
      ['Backoffice', '`npm audit --omit=dev` / complet', st('0 / 2 modérées (outillage)', C.green)],
    ], [1500, 3900, 4238], { firstBold: true }),
    spacer(100),
    P('Les 67 tests ignorés sont conditionnés par des variables `RUN_*_IT` (Formance réel, Sumsub sandbox, Testcontainers) : ils n’ont pas été activés faute de ces services. Aucun test n’échoue sur une assertion : les 13 erreurs sont des défauts de câblage de tests, révélateurs de l’absence de CI.'),
    code([
      '[ERROR] Tests run: 653, Failures: 0, Errors: 13, Skipped: 67',
      'Module \'onboarding\' depends on non-exposed type com.fintech.identity.api.AuthController',
      '  within module \'identity\'! (OnboardingApiErrorHandler.java)',
      'No qualifying bean of type \'com.fintech.identity.IdentityRegistrationService\' available',
    ]),
  ];
}

module.exports = { cover, toc, s1, s2, s3, s4, s5, s6, s7 };
