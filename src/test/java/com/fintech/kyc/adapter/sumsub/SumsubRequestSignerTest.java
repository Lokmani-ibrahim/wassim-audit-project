package com.fintech.kyc.adapter.sumsub;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class SumsubRequestSignerTest {

    @Test
    void generatesCorrectSignature() {

        SumsubRequestSigner signer =
                new SumsubRequestSigner("test-secret");

        String body = """
                {"ttlInSecs":600,"userId":"user-1","levelName":"id-and-liveness"}""";

        String signature = signer.sign(
                1720000000L,
                "POST",
                "/resources/accessTokens/sdk",
                body.getBytes(StandardCharsets.UTF_8)
        );

        assertEquals(
                "48140a792af0d8f5d8f37249ec24388bd207a98db6f25002e44304160ef1706e",
                signature
        );
    }

    @Test
    void differentRequestBodiesProduceDifferentSignatures() {

        SumsubRequestSigner signer =
                new SumsubRequestSigner("test-secret");

        String first = signer.sign(
                1720000000L,
                "POST",
                "/resources/accessTokens/sdk",
                "{}".getBytes(StandardCharsets.UTF_8)
        );

        String second = signer.sign(
                1720000000L,
                "POST",
                "/resources/accessTokens/sdk",
                "{\"userId\":\"123\"}".getBytes(StandardCharsets.UTF_8)
        );

        assertNotEquals(first, second);
    }

    @Test
    void rejectsMissingSecret() {

        assertThrows(
                IllegalArgumentException.class,
                () -> new SumsubRequestSigner("")
        );
    }

    @Test
    void rejectsInvalidUri() {

        SumsubRequestSigner signer =
                new SumsubRequestSigner("test-secret");

        assertThrows(
                IllegalArgumentException.class,
                () -> signer.sign(
                        1720000000L,
                        "GET",
                        "https://api.sumsub.com/resources/applicants",
                        null
                )
        );
    }
}