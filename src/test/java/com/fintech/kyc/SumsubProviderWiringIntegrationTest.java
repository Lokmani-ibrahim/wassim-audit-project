
package com.fintech.kyc;

import com.fintech.kyc.adapter.sumsub.SumsubClient;
import com.fintech.kyc.adapter.sumsub.SumsubKycProvider;
import com.fintech.kyc.port.KycProvider;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "neos.kyc.sumsub.app-token=test-app-token",
        "neos.kyc.sumsub.secret-key=test-secret",
        "neos.kyc.sumsub.level-name=id-and-liveness"
})
class SumsubProviderWiringIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void onlySumsubProviderIsRegistered() {

        Map<String, KycProvider> providers =
                context.getBeansOfType(KycProvider.class);

        assertEquals(
                1,
                providers.size(),
                "Exactly one KYC provider must be registered"
        );

        KycProvider provider =
                providers.values().iterator().next();

        assertInstanceOf(
                SumsubKycProvider.class,
                provider
        );

        assertEquals(
                1,
                context.getBeansOfType(
                        SumsubClient.class
                ).size()
        );
    }
}
