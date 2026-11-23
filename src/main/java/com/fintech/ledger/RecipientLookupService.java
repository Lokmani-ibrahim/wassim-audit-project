package com.fintech.ledger;

import com.fintech.identity.CustomerAccountLookup;
import com.fintech.identity.CustomerAccountSummary;
import com.fintech.identity.IdentityLookupService;
import com.fintech.kyc.CustomerDebitAuthorization;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves an internal-transfer recipient by email.
 *
 * Email only: phone numbers are not verified by this
 * backend (they are self-declared at onboarding), so a
 * phone number cannot identify a recipient.
 *
 * Unknown, ineligible and self recipients all produce the
 * same 404 so the endpoint cannot be used to learn whether
 * an email is registered or what state its account is in.
 */
@Service
public class RecipientLookupService {

    private final IdentityLookupService identityLookupService;
    private final CustomerAccountLookup customerAccountLookup;
    private final CustomerDebitAuthorization authorization;
    private final RecipientLookupRateLimiter rateLimiter;

    public RecipientLookupService(
            IdentityLookupService identityLookupService,
            CustomerAccountLookup customerAccountLookup,
            CustomerDebitAuthorization authorization,
            RecipientLookupRateLimiter rateLimiter
    ) {
        this.identityLookupService =
                Objects.requireNonNull(identityLookupService);

        this.customerAccountLookup =
                Objects.requireNonNull(customerAccountLookup);

        this.authorization =
                Objects.requireNonNull(authorization);

        this.rateLimiter =
                Objects.requireNonNull(rateLimiter);
    }

    public RecipientLookupResult lookup(
            String keycloakSubject,
            String email
    ) {

        Objects.requireNonNull(
                keycloakSubject,
                "keycloakSubject must not be null"
        );

        // Checked first so a caller cannot use the
        // endpoint to load the database.
        if (!rateLimiter.tryAcquire(keycloakSubject)) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too many recipient lookups"
            );
        }

        UUID callerId = identityLookupService
                .findUserIdByKeycloakSubject(keycloakSubject)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.FORBIDDEN,
                                "Customer profile not found"
                        )
                );

        // Same sender rule as internal transfers:
        // KYC APPROVED and account ACTIVE.
        authorization.requireEligible(callerId);

        Optional<CustomerAccountSummary> recipient =
                customerAccountLookup
                        .findByEmail(email)
                        .filter(account ->
                                !callerId.equals(account.userId())
                        );

        if (recipient.isEmpty()
                || !isEligibleRecipient(recipient.get().userId())) {

            throw recipientNotAvailable();
        }

        return new RecipientLookupResult(
                recipient.get().userId(),
                RecipientEmailMasking.mask(
                        recipient.get().email()
                )
        );
    }

    // Same recipient rule the transfer endpoint enforces
    // on destinationUserId.
    private boolean isEligibleRecipient(UUID recipientId) {

        try {
            authorization.requireEligible(recipientId);
            return true;
        } catch (AccessDeniedException exception) {
            return false;
        }
    }

    private static ResponseStatusException recipientNotAvailable() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Recipient not available"
        );
    }
}
