package com.fintech.notifications;

import com.fintech.ledger.SyntheticCreditService;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guard for the Option A test-credit rule
 * (NotificationFeedService.isTestCredit).
 *
 * GET /api/notifications marks every INCOMING_PAYMENT
 * notification as testCredit=true, which is only correct
 * while the back-office SyntheticCreditService is the sole
 * producer of INCOMING_PAYMENT notifications.
 *
 * If this test fails, a new producer (for example a real
 * bank top-up) exists: the feed rule must be replaced by an
 * explicit synthetic marker in the notification payload
 * (request R6) before merging.
 */
class NotificationFeedTestCreditArchitectureTest {

    private static final String NOTIFICATIONS_PACKAGE =
            "com.fintech.notifications";

    @Test
    void syntheticCreditIsTheOnlyIncomingPaymentProducer() {

        JavaClasses classes =
                new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages("com.fintech");

        Set<String> producers =
                new TreeSet<>();

        for (JavaClass source : classes) {

            if (source.getPackageName().startsWith(NOTIFICATIONS_PACKAGE)) {
                // Consumers (email template, feed), not producers.
                continue;
            }

            source.getFieldAccessesFromSelf().forEach(access -> {

                boolean incomingPayment =
                        access.getTarget().getOwner().isEquivalentTo(
                                TransactionNotificationType.class
                        )
                                && access.getTarget().getName().equals(
                                TransactionNotificationType.INCOMING_PAYMENT.name()
                        );

                if (incomingPayment) {
                    producers.add(source.getName());
                }
            });
        }

        assertThat(producers)
                .as(
                        "Classes outside the notifications module that use "
                                + "TransactionNotificationType.INCOMING_PAYMENT. "
                                + "GET /api/notifications marks every INCOMING_PAYMENT "
                                + "as testCredit=true; a new producer needs request R6 "
                                + "(explicit synthetic marker) first."
                )
                .containsExactly(
                        SyntheticCreditService.class.getName()
                );
    }
}
