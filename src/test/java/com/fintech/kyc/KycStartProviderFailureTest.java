
package com.fintech.kyc;

import com.fintech.identity.IdentityLookupService;
import com.fintech.kyc.entity.KycStatus;
import com.fintech.kyc.entity.KycStatusEntity;
import com.fintech.kyc.port.KycProvider;
import com.fintech.kyc.repository.KycStatusRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KycStartProviderFailureTest {

    @Mock
    private IdentityLookupService identityLookup;

    @Mock
    private KycStatusRepository kycStatuses;

    @Mock
    private KycProvider provider;

    private KycStartService service;

    private final UUID userId = UUID.fromString(
            "00000000-0000-0000-0000-000000000091"
    );

    private final String subject =
            "kyc-provider-failure-test";

    @BeforeEach
    void setup() {
        service = new KycStartService(
                identityLookup,
                kycStatuses,
                provider
        );
    }

    private KycStatusEntity givenNotStarted() {

        IdentityLookupService.IdentityProfile profile =
                mock(
                        IdentityLookupService.IdentityProfile.class
                );

        when(profile.userId()).thenReturn(userId);
        when(profile.email()).thenReturn("test@example.com");
        when(profile.phoneNumber()).thenReturn("+21612345678");

        when(identityLookup.findProfileByKeycloakSubject(
                subject
        )).thenReturn(Optional.of(profile));

        KycStatusEntity kyc =
                new KycStatusEntity(userId);

        when(kycStatuses.findByUserId(userId))
                .thenReturn(Optional.of(kyc));

        assertEquals(
                KycStatus.NOT_STARTED,
                kyc.getStatus()
        );

        return kyc;
    }

    private KycProvider.CreateApplicantCommand command() {
        return new KycProvider.CreateApplicantCommand(
                userId,
                "test@example.com",
                "+21612345678"
        );
    }

    @Test
    void providerTimeoutLeavesKycNotStarted() {

        KycStatusEntity kyc = givenNotStarted();

        when(provider.createApplicant(command()))
                .thenThrow(
                        new KycApiException(
                                KycApiErrorCode.SUMSUB_UNAVAILABLE
                        )
                );

        KycApiException exception =
                assertThrows(
                        KycApiException.class,
                        () -> service.start(subject)
                );

        assertEquals(
                KycApiErrorCode.SUMSUB_UNAVAILABLE,
                exception.getErrorCode()
        );

        assertEquals(
                KycStatus.NOT_STARTED,
                kyc.getStatus()
        );

        verify(kycStatuses, never()).save(any());
        verify(kycStatuses, never()).saveAndFlush(any());
    }

    @Test
    void invalidApplicantResponseLeavesKycNotStarted() {

        KycStatusEntity kyc = givenNotStarted();

        when(provider.createApplicant(command()))
                .thenReturn(null);

        KycApiException exception =
                assertThrows(
                        KycApiException.class,
                        () -> service.start(subject)
                );

        assertEquals(
                KycApiErrorCode.SUMSUB_UPSTREAM_ERROR,
                exception.getErrorCode()
        );

        assertEquals(
                KycStatus.NOT_STARTED,
                kyc.getStatus()
        );

        verify(kycStatuses, never()).save(any());
        verify(kycStatuses, never()).saveAndFlush(any());
    }

    @Test
    void laterSuccessfulAttemptCanStartKyc() {

        KycStatusEntity kyc = givenNotStarted();

        when(provider.createApplicant(command()))
                .thenThrow(
                        new KycApiException(
                                KycApiErrorCode.SUMSUB_UNAVAILABLE
                        )
                )
                .thenReturn(
                        new KycProvider.Applicant(
                                "recovered-sumsub-applicant"
                        )
                );

        when(kycStatuses.saveAndFlush(kyc))
                .thenReturn(kyc);

        /*
         * First attempt fails.
         */
        assertThrows(
                KycApiException.class,
                () -> service.start(subject)
        );

        assertEquals(
                KycStatus.NOT_STARTED,
                kyc.getStatus()
        );

        verify(kycStatuses, never()).saveAndFlush(any());

        /*
         * A later request succeeds.
         *
         * In production, SumsubKycProvider performs
         * applicant lookup before attempting creation.
         */
        KycStartResult result = service.start(subject);

        assertEquals("PENDING", result.status());
        assertEquals("SUMSUB", result.provider());

        assertEquals(
                KycStatus.PENDING,
                kyc.getStatus()
        );

        verify(kycStatuses, times(1))
                .saveAndFlush(kyc);
    }
}
