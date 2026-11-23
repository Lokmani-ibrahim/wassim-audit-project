package com.fintech.ledger;

/**
 * Masks an email for display to another customer.
 *
 * Keeps the first character of the local part and the
 * domain: john.doe@example.com -> j***@example.com
 *
 * The rest of the local part is never returned.
 */
public final class RecipientEmailMasking {

    private RecipientEmailMasking() {
    }

    public static String mask(String email) {

        if (email == null) {
            return "***";
        }

        String trimmed = email.trim();
        int at = trimmed.lastIndexOf('@');

        if (at < 1 || at == trimmed.length() - 1) {
            return "***";
        }

        int firstCodePointEnd =
                trimmed.offsetByCodePoints(0, 1);

        return trimmed.substring(0, firstCodePointEnd)
                + "***"
                + trimmed.substring(at);
    }
}
