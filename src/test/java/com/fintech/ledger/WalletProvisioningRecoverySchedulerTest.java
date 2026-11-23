
package com.fintech.ledger;

import com.fintech.kyc.KycApprovedEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WalletProvisioningRecoverySchedulerTest {

    @Mock
    private FailedEventPublications failedPublications;

    private WalletProvisioningRecoveryScheduler scheduler;

    @BeforeEach
    void setup() {
        scheduler = new WalletProvisioningRecoveryScheduler(
                failedPublications,
                50,
                2,
                Duration.ofMinutes(2)
        );
    }

    private ResubmissionOptions captureOptions() {
        ArgumentCaptor<ResubmissionOptions> captor =
                ArgumentCaptor.forClass(
                        ResubmissionOptions.class
                );

        verify(failedPublications).resubmit(
                captor.capture()
        );

        return captor.getValue();
    }

    private EventPublication publication(Object event) {
        EventPublication publication =
                mock(EventPublication.class);

        when(publication.getEvent()).thenReturn(event);

        return publication;
    }

    @Test
    void recoveryUsesBoundedOptions() {
        scheduler.recoverFailedWalletProvisioning();

        ResubmissionOptions options = captureOptions();

        assertEquals(50, options.getBatchSize());
        assertEquals(2, options.getMaxInFlight());
        assertEquals(
                Duration.ofMinutes(2),
                options.getMinAge()
        );
    }

    @Test
    void kycApprovalEventsAreSelected() {
        UUID userId = UUID.randomUUID();

        scheduler.recoverFailedWalletProvisioning();

        assertTrue(
                captureOptions().getFilter().test(
                        publication(
                                new KycApprovedEvent(userId)
                        )
                )
        );
    }

    @Test
    void oldApprovalEventsAreNotFilteredByStatus() {
        UUID previouslyApprovedUser = UUID.randomUUID();

        scheduler.recoverFailedWalletProvisioning();

        // Revocation safety is enforced in the listener.
        // The scheduler must permit the event to be consumed.
        assertTrue(
                captureOptions().getFilter().test(
                        publication(
                                new KycApprovedEvent(
                                        previouslyApprovedUser
                                )
                        )
                )
        );
    }

    @Test
    void unrelatedEventsAreExcluded() {
        scheduler.recoverFailedWalletProvisioning();

        assertFalse(
                captureOptions().getFilter().test(
                        publication("unrelated-event")
                )
        );
    }

    @Test
    void registryFailureDoesNotEscapeScheduler() {
        doThrow(
                new IllegalStateException(
                        "Registry unavailable"
                )
        ).when(failedPublications)
                .resubmit(any(ResubmissionOptions.class));

        assertDoesNotThrow(
                scheduler::recoverFailedWalletProvisioning
        );
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new WalletProvisioningRecoveryScheduler(
                        failedPublications,
                        0,
                        2,
                        Duration.ofMinutes(2)
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new WalletProvisioningRecoveryScheduler(
                        failedPublications,
                        50,
                        0,
                        Duration.ofMinutes(2)
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new WalletProvisioningRecoveryScheduler(
                        failedPublications,
                        50,
                        2,
                        Duration.ofSeconds(-1)
                )
        );
    }
}
