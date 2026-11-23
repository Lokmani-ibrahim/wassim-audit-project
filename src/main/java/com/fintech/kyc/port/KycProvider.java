package com.fintech.kyc.port;

import java.util.UUID;

public interface KycProvider {

    Applicant createApplicant(
            CreateApplicantCommand command
    );

    VerificationSession createVerificationSession(
            CreateVerificationSessionCommand command
    );

    record CreateApplicantCommand(
            UUID userId,
            String email,
            String phoneNumber
    ) {
    }

    record Applicant(
            String providerApplicantId
    ) {
    }

    record CreateVerificationSessionCommand(
            UUID userId
    ) {
    }

    record VerificationSession(
            String token,
            long expiresInSeconds
    ) {
    }
}