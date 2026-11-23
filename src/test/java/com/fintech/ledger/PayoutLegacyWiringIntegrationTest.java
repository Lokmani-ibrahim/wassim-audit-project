
package com.fintech.ledger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class PayoutLegacyWiringIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void legacySettlementServiceIsNotRegistered() {

        assertTrue(
                context.getBeansOfType(
                        PayoutSettlementService.class
                ).isEmpty(),
                "Legacy direct settlement must not be a Spring bean"
        );
    }

    @Test
    void durableReservationCoordinatorIsRegistered() {

        assertFalse(
                context.getBeansOfType(
                        PayoutReservationExecutionService.class
                ).isEmpty()
        );
    }
}
