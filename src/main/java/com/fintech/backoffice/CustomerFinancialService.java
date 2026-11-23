package com.fintech.backoffice;

import com.fintech.identity.CustomerDirectoryLookup;
import com.fintech.ledger.CustomerFinancialRead;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

@Service
public class CustomerFinancialService {
    private final CustomerDirectoryLookup customers;
    private final CustomerFinancialRead ledger;
    private final byte[] cursorKey = new byte[32];

    public CustomerFinancialService(CustomerDirectoryLookup customers, CustomerFinancialRead ledger) {
        this.customers = customers;
        this.ledger = ledger;
        new SecureRandom().nextBytes(cursorKey);
    }

    public CustomerFinancialRead.WalletPage wallets(UUID userId) {
        requireCustomer(userId);
        return ledger.wallets(userId);
    }

    public CustomerFinancialRead.TransactionPage transactions(
            UUID userId, String currency, int pageSize, String cursor) {
        requireCustomer(userId);
        if (currency == null || !currency.matches("EUR|USD|GBP|AED|MUR|TND"))
            throw new IllegalArgumentException("Invalid currency");
        if (pageSize < 1 || pageSize > 100)
            throw new IllegalArgumentException("Invalid pageSize");
        String providerCursor = decodeCursor(userId, currency, pageSize, cursor);
        CustomerFinancialRead.TransactionPage result = ledger.transactions(
                userId, currency, pageSize, providerCursor);
        if (result.hasMore() && (result.nextCursor() == null
                || result.nextCursor().isBlank()))
            throw new IllegalStateException("Provider returned no continuation cursor");
        String next = result.hasMore() ? encodeCursor(
                userId, currency, pageSize, result.nextCursor()) : null;
        return new CustomerFinancialRead.TransactionPage(result.items(), result.hasMore(), next);
    }

    private void requireCustomer(UUID userId) {
        if (customers.find(userId).isEmpty()) throw new CustomerNotFoundException();
    }

    private String encodeCursor(UUID userId, String currency, int pageSize, String providerCursor) {
        String provider = Base64.getUrlEncoder().withoutPadding().encodeToString(
                providerCursor.getBytes(StandardCharsets.UTF_8));
        String payload = "1|" + userId + "|" + currency + "|" + pageSize + "|" + provider;
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (payload + "|" + signature).getBytes(StandardCharsets.UTF_8));
    }

    private String decodeCursor(UUID userId, String currency, int pageSize, String cursor) {
        if (cursor == null) return null;
        if (cursor.length() > 8192 || !cursor.matches("[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("Invalid cursor");
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", -1);
            if (parts.length != 6 || !parts[0].equals("1") ||
                    !parts[1].equals(userId.toString()) || !parts[2].equals(currency) ||
                    !parts[3].equals(Integer.toString(pageSize)) || parts[4].isEmpty())
                throw new IllegalArgumentException("Cursor does not match request");
            String payload = String.join("|", parts[0], parts[1], parts[2], parts[3], parts[4]);
            byte[] supplied = Base64.getUrlDecoder().decode(parts[5]);
            if (!MessageDigest.isEqual(sign(payload), supplied))
                throw new IllegalArgumentException("Invalid cursor signature");
            return new String(Base64.getUrlDecoder().decode(parts[4]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid cursor", exception);
        }
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(cursorKey, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("Cursor signing unavailable", exception);
        }
    }
}
