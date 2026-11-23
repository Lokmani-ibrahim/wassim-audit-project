
package com.fintech.kyc;

import com.fintech.identity.IdentityLookupService;
import com.fintech.kyc.entity.KycStatus;
import com.fintech.kyc.entity.KycStatusEntity;
import com.fintech.kyc.port.KycProvider;
import com.fintech.kyc.repository.KycStatusRepository;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KycStartServiceSumsubTest {

    @Test
    void sumsubStartMustNotApproveCustomer() {

        UUID userId = UUID.randomUUID();
        String subject = "test-keycloak-subject";

        IdentityLookupService identity =
                mock(IdentityLookupService.class);

        KycStatusRepository repository =
                mock(KycStatusRepository.class);

        KycProvider provider =
                mock(KycProvider.class);

        KycStatusEntity entity =
                new KycStatusEntity(userId);

        when(identity.findProfileByKeycloakSubject(subject))
                .thenReturn(Optional.of(
                        new IdentityLookupService.IdentityProfile(
                                userId,
                                "test@example.com",
                                "+21612345678"
                        )
                ));

        when(repository.findByUserId(userId))
                .thenReturn(Optional.of(entity));

        when(provider.createApplicant(any()))
                .thenReturn(
                        new KycProvider.Applicant(
                                "sandbox-applicant-123"
                        )
                );

        when(repository.saveAndFlush(entity))
                .thenReturn(entity);

        KycStartService service = new KycStartService(
                identity,
                repository,
                provider
        );

        KycStartResult result = service.start(subject);

        assertEquals("PENDING", result.status());
        assertEquals("SUMSUB", result.provider());

        assertEquals(
                KycStatus.PENDING,
                entity.getStatus()
        );

        assertEquals(
                "sandbox-applicant-123",
                entity.getProviderApplicantId()
        );

        assertNull(entity.getDecidedAt());

        verify(provider, times(1))
                .createApplicant(any());

        verify(repository, times(1))
                .saveAndFlush(entity);
    }

    @Test
    void sumsubFailureCannotApproveCustomer() {

        UUID userId = UUID.randomUUID();
        String subject = "sumsub-failure";

        IdentityLookupService identity =
                mock(IdentityLookupService.class);

        KycStatusRepository repository =
                mock(KycStatusRepository.class);

        KycProvider provider =
                mock(KycProvider.class);

        KycStatusEntity entity =
                new KycStatusEntity(userId);

        when(identity.findProfileByKeycloakSubject(subject))
                .thenReturn(Optional.of(
                        new IdentityLookupService.IdentityProfile(
                                userId,
                                "test@example.com",
                                null
                        )
                ));

        when(repository.findByUserId(userId))
                .thenReturn(Optional.of(entity));

        when(provider.createApplicant(any()))
                .thenThrow(
                        new IllegalStateException(
                                "Sumsub unavailable"
                        )
                );

        KycStartService service = new KycStartService(
                identity,
                repository,
                provider
        );

        assertThrows(
                IllegalStateException.class,
                () -> service.start(subject)
        );

        assertEquals(
                KycStatus.NOT_STARTED,
                entity.getStatus()
        );

        verify(repository, never())
                .saveAndFlush(any());
    }
}
