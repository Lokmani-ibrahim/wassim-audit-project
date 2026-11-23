
package com.fintech.kyc;

import com.fintech.kyc.adapter.sumsub.SumsubProperties;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        SumsubProperties.class
})
public class KycConfiguration {
}
