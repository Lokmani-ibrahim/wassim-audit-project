package com.fintech.kyc.adapter.sumsub;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

public final class SumsubRequestSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec secretKey;

    public SumsubRequestSigner(String secret) {

        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException(
                    "Sumsub secret key must not be blank"
            );
        }

        this.secretKey = new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8),
                ALGORITHM
        );
    }

    public String sign(
            long timestamp,
            String method,
            String uri,
            byte[] body
    ) {

        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(uri, "uri");

        if (timestamp <= 0) {
            throw new IllegalArgumentException(
                    "Timestamp must be positive"
            );
        }

        if (!uri.startsWith("/") || uri.startsWith("//")) {
            throw new IllegalArgumentException(
                    "URI must be a relative API path"
            );
        }

        String prefix =
                timestamp
                        + method.toUpperCase(Locale.ROOT)
                        + uri;

        try {

            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(secretKey);

            mac.update(
                    prefix.getBytes(StandardCharsets.UTF_8)
            );

            if (body != null) {
                mac.update(body);
            }

            return HexFormat.of().formatHex(
                    mac.doFinal()
            );

        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "Unable to sign Sumsub API request",
                    exception
            );
        }
    }
}