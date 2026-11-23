package com.fintech.ledger.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/*
 * No @Email constraint: the value is trimmed and matched
 * case-insensitively by the service, and a value that is
 * not a registered email is simply "not available".
 */
public record RecipientLookupRequest(

        @NotBlank
        @Size(max = 320)
        String email

) {
}
