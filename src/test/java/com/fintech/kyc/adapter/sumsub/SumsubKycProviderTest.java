
package com.fintech.kyc.adapter.sumsub;

import com.fintech.kyc.KycApiErrorCode;
import com.fintech.kyc.KycApiException;
import com.fintech.kyc.port.KycProvider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SumsubKycProviderTest {

    private SumsubClient client;
    private SumsubKycProvider provider;

    private final UUID userId = UUID.fromString(
            "00000000-0000-0000-0000-000000000001"
    );

    @BeforeEach
    void setup() {

        client = mock(SumsubClient.class);

        SumsubProperties properties =
                new SumsubProperties(
                        "https://api.sumsub.com",
                        "test-token",
                        "test-secret",
                        "id-and-liveness",
                        600
                );

        provider = new SumsubKycProvider(
                client,
                properties
        );
    }

    private KycProvider.CreateApplicantCommand command() {

        return new KycProvider.CreateApplicantCommand(
                userId,
                "test@example.com",
                "+21612345678"
        );
    }

    private KycProvider.CreateVerificationSessionCommand
    sdkCommand() {

        return new KycProvider
                .CreateVerificationSessionCommand(userId);
    }

    private void assertKycError(
            KycApiErrorCode expected,
            Executable executable
    ) {

        KycApiException exception =
                assertThrows(
                        KycApiException.class,
                        executable
                );

        assertEquals(
                expected,
                exception.getErrorCode()
        );

        assertEquals(
                expected.status(),
                exception.getStatusCode()
        );

        assertEquals(
                expected.message(),
                exception.getReason()
        );
    }

    @Test
    void reusesExistingApplicant() {

        when(client.findApplicantId(userId))
                .thenReturn(Optional.of("existing-id"));

        var result = provider.createApplicant(command());

        assertEquals(
                "existing-id",
                result.providerApplicantId()
        );

        verify(client, never())
                .createApplicant(any(), any(), any());
    }

    @Test
    void createsNewApplicantWhenMissing() {

        when(client.findApplicantId(userId))
                .thenReturn(Optional.empty());

        when(client.createApplicant(
                userId,
                "test@example.com",
                "+21612345678"
        )).thenReturn("new-applicant-id");

        var result = provider.createApplicant(command());

        assertEquals(
                "new-applicant-id",
                result.providerApplicantId()
        );

        verify(client, times(1))
                .createApplicant(
                        userId,
                        "test@example.com",
                        "+21612345678"
                );
    }

    @Test
    void recoversApplicantAfterConflict() {

        when(client.findApplicantId(userId))
                .thenReturn(
                        Optional.empty(),
                        Optional.of("recovered-id")
                );

        when(client.createApplicant(
                userId,
                "test@example.com",
                "+21612345678"
        )).thenThrow(
                new HttpClientErrorException(
                        HttpStatus.CONFLICT
                )
        );

        var result = provider.createApplicant(command());

        assertEquals(
                "recovered-id",
                result.providerApplicantId()
        );

        verify(client, times(2))
                .findApplicantId(userId);

        verify(client, times(1))
                .createApplicant(
                        userId,
                        "test@example.com",
                        "+21612345678"
                );
    }

    @Test
    void unresolvedConflictReturnsBadGateway() {

        when(client.findApplicantId(userId))
                .thenReturn(Optional.empty());

        when(client.createApplicant(
                userId,
                "test@example.com",
                "+21612345678"
        )).thenThrow(
                new HttpClientErrorException(
                        HttpStatus.CONFLICT
                )
        );

        assertKycError(
                KycApiErrorCode.SUMSUB_UPSTREAM_ERROR,
                () -> provider.createApplicant(command())
        );

        verify(client, times(2))
                .findApplicantId(userId);

        verify(client, times(1))
                .createApplicant(
                        userId,
                        "test@example.com",
                        "+21612345678"
                );
    }

    @Test
    void authenticationErrorReturnsSafeBadGateway() {

        when(client.findApplicantId(userId))
                .thenReturn(Optional.empty());

        when(client.createApplicant(
                userId,
                "test@example.com",
                "+21612345678"
        )).thenThrow(
                new HttpClientErrorException(
                        HttpStatus.UNAUTHORIZED
                )
        );

        assertKycError(
                KycApiErrorCode.SUMSUB_UPSTREAM_ERROR,
                () -> provider.createApplicant(command())
        );
    }

    @Test
    void applicantLookupTimeoutReturnsUnavailable() {

        when(client.findApplicantId(userId))
                .thenThrow(
                        new ResourceAccessException(
                                "Simulated connection timeout"
                        )
                );

        assertKycError(
                KycApiErrorCode.SUMSUB_UNAVAILABLE,
                () -> provider.createApplicant(command())
        );

        verify(client, never())
                .createApplicant(any(), any(), any());
    }

    @Test
    void applicantCreationTimeoutNeverRetriesPost() {

        when(client.findApplicantId(userId))
                .thenReturn(Optional.empty());

        when(client.createApplicant(
                userId,
                "test@example.com",
                "+21612345678"
        )).thenThrow(
                new ResourceAccessException(
                        "Simulated read timeout"
                )
        );

        assertKycError(
                KycApiErrorCode.SUMSUB_UNAVAILABLE,
                () -> provider.createApplicant(command())
        );

        verify(client, times(1))
                .createApplicant(
                        userId,
                        "test@example.com",
                        "+21612345678"
                );
    }

    @Test
    void applicantCreationRateLimitReturnsUnavailable() {

        when(client.findApplicantId(userId))
                .thenReturn(Optional.empty());

        when(client.createApplicant(
                userId,
                "test@example.com",
                "+21612345678"
        )).thenThrow(
                new HttpClientErrorException(
                        HttpStatus.TOO_MANY_REQUESTS
                )
        );

        assertKycError(
                KycApiErrorCode.SUMSUB_UNAVAILABLE,
                () -> provider.createApplicant(command())
        );

        verify(client, times(1))
                .createApplicant(
                        userId,
                        "test@example.com",
                        "+21612345678"
                );
    }

    @Test
    void applicantLookupServerErrorReturnsUnavailable() {

        when(client.findApplicantId(userId))
                .thenThrow(
                        new HttpServerErrorException(
                                HttpStatus.SERVICE_UNAVAILABLE
                        )
                );

        assertKycError(
                KycApiErrorCode.SUMSUB_UNAVAILABLE,
                () -> provider.createApplicant(command())
        );
    }

    @Test
    void createsVerificationSession() {

        when(client.generateSdkToken(userId))
                .thenReturn(
                        new SumsubClient.SdkTokenResponse(
                                "test-sdk-token",
                                userId.toString()
                        )
                );

        var session =
                provider.createVerificationSession(
                        sdkCommand()
                );

        assertEquals(
                "test-sdk-token",
                session.token()
        );

        assertEquals(
                600,
                session.expiresInSeconds()
        );
    }

    @Test
    void sdkTimeoutReturnsUnavailable() {

        when(client.generateSdkToken(userId))
                .thenThrow(
                        new ResourceAccessException(
                                "Simulated SDK read timeout"
                        )
                );

        assertKycError(
                KycApiErrorCode.SUMSUB_UNAVAILABLE,
                () -> provider.createVerificationSession(
                        sdkCommand()
                )
        );
    }

    @Test
    void sdkServerFailureReturnsUnavailable() {

        when(client.generateSdkToken(userId))
                .thenThrow(
                        new HttpServerErrorException(
                                HttpStatus.BAD_GATEWAY
                        )
                );

        assertKycError(
                KycApiErrorCode.SUMSUB_UNAVAILABLE,
                () -> provider.createVerificationSession(
                        sdkCommand()
                )
        );
    }

    @Test
    void sdkUnexpectedClientErrorReturnsBadGateway() {

        when(client.generateSdkToken(userId))
                .thenThrow(
                        new HttpClientErrorException(
                                HttpStatus.BAD_REQUEST
                        )
                );

        assertKycError(
                KycApiErrorCode.SUMSUB_UPSTREAM_ERROR,
                () -> provider.createVerificationSession(
                        sdkCommand()
                )
        );
    }

    @Test
    void malformedSdkResponseReturnsBadGateway() {

        when(client.generateSdkToken(userId))
                .thenThrow(
                        new IllegalStateException(
                                "Malformed provider response"
                        )
                );

        assertKycError(
                KycApiErrorCode.SUMSUB_UPSTREAM_ERROR,
                () -> provider.createVerificationSession(
                        sdkCommand()
                )
        );
    }

    @Test
    void sdkTokenForDifferentUserIsRejected() {

        when(client.generateSdkToken(userId))
                .thenReturn(
                        new SumsubClient.SdkTokenResponse(
                                "test-sdk-token",
                                UUID.randomUUID().toString()
                        )
                );

        assertKycError(
                KycApiErrorCode.SUMSUB_UPSTREAM_ERROR,
                () -> provider.createVerificationSession(
                        sdkCommand()
                )
        );
    }

    @Test
    void nextRequestRecoversApplicantAfterPreviousTimeout() {

        /*
         * First request:
         * - Applicant lookup returns empty.
         * - Creation times out.
         *
         * The applicant might still have been created
         * remotely by Sumsub.
         *
         * Second request:
         * - Lookup discovers the existing applicant.
         * - No second creation POST is performed.
         */
        when(client.findApplicantId(userId))
                .thenReturn(
                        Optional.empty(),
                        Optional.of("applicant-created-remotely")
                );

        when(client.createApplicant(
                userId,
                "test@example.com",
                "+21612345678"
        )).thenThrow(
                new ResourceAccessException(
                        "Simulated read timeout"
                )
        );

        KycApiException firstFailure =
                assertThrows(
                        KycApiException.class,
                        () -> provider.createApplicant(command())
                );

        assertEquals(
                KycApiErrorCode.SUMSUB_UNAVAILABLE,
                firstFailure.getErrorCode()
        );

        /*
         * Simulate the next mobile request.
         */
        KycProvider.Applicant recovered =
                provider.createApplicant(command());

        assertEquals(
                "applicant-created-remotely",
                recovered.providerApplicantId()
        );

        /*
         * Two GET lookups, but only one creation POST
         * across both requests.
         */
        verify(client, times(2))
                .findApplicantId(userId);

        verify(client, times(1))
                .createApplicant(
                        userId,
                        "test@example.com",
                        "+21612345678"
                );
    }

}
