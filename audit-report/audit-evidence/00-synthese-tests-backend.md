# Synthèse des tests backend

## Passage 1 — sans variables SUMSUB_* (PostgreSQL 16 éphémère)
[ERROR] Tests run: 653, Failures: 0, Errors: 254, Skipped: 66
Cause principale : Sumsub secret key must not be blank (90 occurrences)

## Passage 2 — SUMSUB_APP_TOKEN/SUMSUB_SECRET_KEY = valeurs factices
[ERROR] Tests run: 653, Failures: 0, Errors: 13, Skipped: 67

Classes en erreur :
- com.fintech.identity.security.BackofficeMeSecurityTest : 12 erreurs sur 12
- com.fintech.ModularityTest : 1 erreurs sur 1

ModularityTest :
org.springframework.modulith.core.Violations: 
- Module 'onboarding' depends on non-exposed type com.fintech.identity.api.AuthController within module 'identity'!
Class <com.fintech.onboarding.api.OnboardingApiErrorHandler> has annotation member of type <com.fintech.identity.api.AuthController> in (OnboardingApiErrorHandler.java:0)

BackofficeMeSecurityTest :
No qualifying bean of type 'com.fintech.identity.IdentityRegistrationService'
