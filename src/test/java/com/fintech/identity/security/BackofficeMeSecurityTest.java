package com.fintech.identity.security;

import com.fintech.identity.api.BackofficeMeController;
import com.fintech.backoffice.api.CustomerDirectoryController;
import com.fintech.backoffice.api.CustomerFinancialController;
import com.fintech.backoffice.CustomerDirectoryService;
import com.fintech.backoffice.CustomerFinancialService;
import com.fintech.identity.api.AuthController;
import com.fintech.ledger.SyntheticCreditService;
import com.fintech.ledger.CustomerTransferManualReconciliationService;
import com.fintech.ledger.CustomerTransferReconciliationInspectionService;
import com.fintech.ledger.api.CustomerTransferReconciliationAdminController;
import com.fintech.ledger.api.SyntheticCreditController;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {BackofficeMeController.class, CustomerDirectoryController.class,
        CustomerFinancialController.class, AuthController.class,
        SyntheticCreditController.class, CustomerTransferReconciliationAdminController.class}, properties = {
        "neos.backoffice.enabled=true",
        "neos.backoffice.audience=neos-api",
        "neos.backoffice.client-id=neos-backoffice",
        "neos.backoffice.issuer=https://issuer.example/realms/neos",
        "neos.backoffice.synthetic-credit.enabled=true",
        "neos.transfers.reconciliation.admin-api-enabled=true"
})
@Import({SecurityConfig.class, StaffAccessPolicy.class, BackofficeMeSecurityTest.DecoderConfig.class})
class BackofficeMeSecurityTest {

    private static final String ISSUER = "https://issuer.example/realms/neos";
    private static final KeyPair keys = createKeys();

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SyntheticCreditService syntheticCreditService;

    @MockitoBean
    private CustomerTransferReconciliationInspectionService inspectionService;

    @MockitoBean
    private CustomerTransferManualReconciliationService reconciliationService;

    @MockitoBean
    private CustomerDirectoryService customerDirectoryService;

    @MockitoBean
    private CustomerFinancialService customerFinancialService;

    private static KeyPair createKeys() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void missingAndInvalidJwtReturn401() throws Exception {
        mvc.perform(get("/api/backoffice/me"))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
        mvc.perform(get("/api/backoffice/me").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
    }

    @Test
    void decoderRejectsWrongIssuerSignatureAndExpiry() throws Exception {
        mvc.perform(bearer(token("https://wrong.example/realms/neos", "neos-api", "neos-backoffice",
                        List.of("COMPLIANCE_OFFICER"), 300, keys)))
                .andExpect(status().isUnauthorized());
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        mvc.perform(bearer(token(ISSUER, "neos-api", "neos-backoffice",
                        List.of("COMPLIANCE_OFFICER"), 300, generator.generateKeyPair())))
                .andExpect(status().isUnauthorized());
        mvc.perform(bearer(token(ISSUER, "neos-api", "neos-backoffice",
                        List.of("COMPLIANCE_OFFICER"), -300, keys)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validJwtWithWrongStaffClaimsReturns403() throws Exception {
        for (String role : List.of("CUSTOMER", "OPERATIONS", "RECONCILIATION_ADMIN")) {
            mvc.perform(bearer(token(ISSUER, "neos-api", "neos-backoffice", List.of(role), 300, keys)))
                    .andExpect(status().isForbidden()).andExpect(content().string(""));
        }
        mvc.perform(bearer(token(ISSUER, "wrong-api", "neos-backoffice",
                        List.of("COMPLIANCE_OFFICER"), 300, keys)))
                .andExpect(status().isForbidden());
        mvc.perform(bearer(token(ISSUER, "neos-api", "neos-mobile",
                        List.of("COMPLIANCE_OFFICER"), 300, keys)))
                .andExpect(status().isForbidden());
    }

    @Test
    void dedicatedStaffTokensReturnScopedMe() throws Exception {
        mvc.perform(bearer(token(ISSUER, "neos-api", "neos-backoffice",
                        List.of("COMPLIANCE_OFFICER"), 300, keys)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staffSubject").value("staff-123"))
                .andExpect(jsonPath("$.capabilities[0]").value("CUSTOMER_READ"));
        mvc.perform(bearer(token(ISSUER, "neos-api", "neos-backoffice",
                        List.of("ADMIN"), 300, keys)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capabilities").isEmpty());
    }

    @Test
    void customerDirectoryDirectRoutesRequireDedicatedCompliance() throws Exception {
        org.mockito.Mockito.when(customerDirectoryService.list(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new CustomerDirectoryService.CustomerPage(List.of(), null));
        for (String path : List.of("/api/backoffice/customers",
                "/api/backoffice/customers/00000000-0000-0000-0000-000000000001",
                "/api/backoffice/customers/00000000-0000-0000-0000-000000000001/wallets",
                "/api/backoffice/customers/00000000-0000-0000-0000-000000000001/transactions?currency=EUR")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            for (List<String> roles : List.of(List.of("ADMIN"), List.of("CUSTOMER"),
                    List.of("ADMIN", "CUSTOMER"), List.of("COMPLIANCE_OFFICER", "CUSTOMER"),
                    List.of("ADMIN", "COMPLIANCE_OFFICER"), List.of("OPERATIONS"))) {
                mvc.perform(get(path).header("Authorization", "Bearer " +
                        token(ISSUER, "neos-api", "neos-backoffice", roles, 300, keys)))
                        .andExpect(status().isForbidden());
            }
        }
        mvc.perform(get("/api/backoffice/customers").header("Authorization", "Bearer " +
                token(ISSUER, "neos-api", "neos-backoffice", List.of("COMPLIANCE_OFFICER"), 300, keys)))
                .andExpect(status().isOk());
        org.mockito.Mockito.when(customerDirectoryService.detail(
                java.util.UUID.fromString("00000000-0000-0000-0000-000000000001")))
                .thenReturn(new CustomerDirectoryService.CustomerRow(
                        java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        "invented@review.invalid", "ACTIVE", null, Instant.parse("2026-11-09T10:00:00Z")));
        mvc.perform(get("/api/backoffice/customers/00000000-0000-0000-0000-000000000001")
                .header("Authorization", "Bearer " + token(ISSUER, "neos-api",
                        "neos-backoffice", List.of("COMPLIANCE_OFFICER"), 300, keys)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("invented@review.invalid"));
        org.mockito.Mockito.verifyNoInteractions(syntheticCreditService);
    }

    @Test
    void databaseFailureReturnsSanitized503() throws Exception {
        org.mockito.Mockito.when(customerDirectoryService.list(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("internal database detail"));
        mvc.perform(get("/api/backoffice/customers").header("Authorization", "Bearer " +
                token(ISSUER, "neos-api", "neos-backoffice", List.of("COMPLIANCE_OFFICER"), 300, keys)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("CUSTOMER_DIRECTORY_UNAVAILABLE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("internal database detail"))));
    }

    @Test
    void mixedStaffTokensAreForbidden() throws Exception {
        for (List<String> roles : List.of(List.of("ADMIN", "CUSTOMER"),
                List.of("COMPLIANCE_OFFICER", "CUSTOMER"),
                List.of("ADMIN", "COMPLIANCE_OFFICER"))) {
            mvc.perform(bearer(token(ISSUER, "neos-api", "neos-backoffice", roles, 300, keys)))
                    .andExpect(status().isForbidden()).andExpect(content().string(""));
        }
    }

    @Test
    void customerInAnotherSupportedClaimLocationIsForbidden() throws Exception {
        mvc.perform(bearer(token(ISSUER, "neos-api", "neos-backoffice", List.of("ADMIN"),
                        Map.of("neos-mobile", Map.of("roles", List.of("CUSTOMER"))), 300, keys)))
                .andExpect(status().isForbidden());
        mvc.perform(bearer(token(ISSUER, "neos-api", "neos-backoffice", List.of(),
                        Map.of("neos-backoffice", Map.of("roles", List.of("ADMIN"))), 300, keys)))
                .andExpect(status().isOk());
    }

    @Test
    void adminDoesNotGainComplianceOnlyAction() throws Exception {
        mvc.perform(post("/api/backoffice/synthetic-credits")
                .header("Authorization", "Bearer " +
                                token(ISSUER, "neos-api", "neos-backoffice", List.of("ADMIN"), 300, keys))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"00000000-0000-0000-0000-000000000001\",\"currency\":\"EUR\",\"amountMinor\":1,\"operationId\":\"admin_denied\"}"))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(syntheticCreditService);
    }

    @Test
    void customerEndpointKeepsItsExistingRoleContract() throws Exception {
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " +
                        token(ISSUER, "mobile-audience", "neos-mobile", List.of("CUSTOMER"), 300, keys)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.subject").value("staff-123"));
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " +
                        token(ISSUER, "neos-api", "neos-backoffice", List.of("COMPLIANCE_OFFICER"), 300, keys)))
                .andExpect(status().isForbidden());
    }

    @Test
    void syntheticCreditRouteRetainsItsOwnRoleCheckWithoutStaffAudienceOrClientGate() throws Exception {
        mvc.perform(post("/api/backoffice/synthetic-credits")
                        .header("Authorization", "Bearer " +
                                token(ISSUER, "legacy-audience", "neos-mobile",
                                        List.of("COMPLIANCE_OFFICER"), 300, keys))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reconciliationAdminRouteRetainsItsOwnRoleCheckWithoutStaffAudienceOrClientGate() throws Exception {
        String path = "/api/admin/transfers/reconciliation";
        mvc.perform(get(path).param("senderId", "00000000-0000-0000-0000-000000000001")
                        .header("Authorization", "Bearer " +
                                token(ISSUER, "legacy-audience", "neos-mobile",
                                        List.of("RECONCILIATION_ADMIN"), 300, keys)))
                .andExpect(status().isOk());
        mvc.perform(get(path).param("senderId", "00000000-0000-0000-0000-000000000001")
                        .header("Authorization", "Bearer " +
                                token(ISSUER, "neos-api", "neos-backoffice",
                                        List.of("COMPLIANCE_OFFICER"), 300, keys)))
                .andExpect(status().isForbidden());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder bearer(String token) {
        return get("/api/backoffice/me").header("Authorization", "Bearer " + token);
    }

    private String token(String issuer, String audience, String client, List<String> roles,
                         long expiresInSeconds, KeyPair signingKeys) throws Exception {
        return token(issuer, audience, client, roles, Map.of(), expiresInSeconds, signingKeys);
    }

    private String token(String issuer, String audience, String client, List<String> roles,
                         Map<String, Object> resourceAccess, long expiresInSeconds,
                         KeyPair signingKeys) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer).subject("staff-123").audience(audience)
                .issueTime(Date.from(now.minusSeconds(10)))
                .expirationTime(Date.from(now.plusSeconds(expiresInSeconds)))
                .claim("azp", client)
                .claim("preferred_username", "test.staff")
                .claim("email", "staff@example.invalid")
                .claim("realm_access", Map.of("roles", roles))
                .claim("resource_access", resourceAccess)
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner(signingKeys.getPrivate()));
        return jwt.serialize();
    }

    @TestConfiguration
    static class DecoderConfig {
        @Bean
        JwtDecoder jwtDecoder() {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keys.getPublic()).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
            return decoder;
        }
    }
}
