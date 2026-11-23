package com.fintech.kyc.adapter.sumsub;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class SumsubWebhookSignatureVerifierTest {

    private static final String SECRET =
            "test-webhook-secret";

    private static final String BODY = """
            {"type":"applicantReviewed","applicantId":"abc","reviewResult":{"reviewAnswer":"GREEN"}}\
            """;

    private static final String VALID_DIGEST =
            "94dd19226cbc506024960151ad034da4c"
                    + "374dc5c60e66261e8d9d7934b055e68";

    private final SumsubWebhookSignatureVerifier verifier =
            new SumsubWebhookSignatureVerifier(SECRET);

    private byte[] body() {
        return BODY.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void acceptsValidSignature() {

        assertTrue(verifier.verify(
                body(),
                VALID_DIGEST,
                "HMAC_SHA256_HEX"
        ));
    }

    @Test
    void rejectsTamperedPayload() {

        byte[] modified = (BODY + " ")
                .getBytes(StandardCharsets.UTF_8);

        assertFalse(verifier.verify(
                modified,
                VALID_DIGEST,
                "HMAC_SHA256_HEX"
        ));
    }

    @Test
    void rejectsIncorrectDigest() {

        assertFalse(verifier.verify(
                body(),
                "00".repeat(32),
                "HMAC_SHA256_HEX"
        ));
    }

    @Test
    void rejectsUnsupportedAlgorithm() {

        assertFalse(verifier.verify(
                body(),
                VALID_DIGEST,
                "HMAC_SHA1_HEX"
        ));
    }

    @Test
    void rejectsMalformedDigest() {

        assertFalse(verifier.verify(
                body(),
                "not-a-hex-digest",
                "HMAC_SHA256_HEX"
        ));
    }

    @Test
    void rejectsMissingHeaders() {

        assertFalse(verifier.verify(
                body(),
                null,
                null
        ));
    }

    @Test
    void rejectsWrongSecret() {

        var wrongVerifier =
                new SumsubWebhookSignatureVerifier(
                        "different-secret"
                );

        assertFalse(wrongVerifier.verify(
                body(),
                VALID_DIGEST,
                "HMAC_SHA256_HEX"
        ));
    }
}