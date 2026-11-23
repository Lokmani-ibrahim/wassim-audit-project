package com.fintech.kyc.adapter.sumsub;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class SumsubWebhookSignatureVerifier {

    private static final String ALGORITHM = "HmacSHA256";

    private static final String HEADER_ALGORITHM =
            "HMAC_SHA256_HEX";

    private final SecretKeySpec secretKey;

    public SumsubWebhookSignatureVerifier(String webhookSecret) {

        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalArgumentException(
                    "Sumsub webhook secret must not be blank"
            );
        }

        this.secretKey = new SecretKeySpec(
                webhookSecret.getBytes(StandardCharsets.UTF_8),
                ALGORITHM
        );
    }

    public boolean verify(
            byte[] rawBody,
            String receivedDigest,
            String receivedAlgorithm
    ) {

        if (rawBody == null || rawBody.length == 0) {
            return false;
        }

        if (receivedDigest == null
                || receivedAlgorithm == null) {
            return false;
        }

        // We explicitly require SHA-256.
        if (!HEADER_ALGORITHM.equals(receivedAlgorithm)) {
            return false;
        }

        try {
            byte[] providedDigest = HexFormat.of()
                    .parseHex(receivedDigest);

            if (providedDigest.length != 32) {
                return false;
            }

            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(secretKey);

            byte[] expectedDigest = mac.doFinal(rawBody);

            return MessageDigest.isEqual(
                    expectedDigest,
                    providedDigest
            );

        } catch (IllegalArgumentException exception) {

            // Invalid hexadecimal digest.
            return false;

        } catch (GeneralSecurityException exception) {

            throw new IllegalStateException(
                    "Unable to verify Sumsub webhook signature",
                    exception
            );
        }
    }
}