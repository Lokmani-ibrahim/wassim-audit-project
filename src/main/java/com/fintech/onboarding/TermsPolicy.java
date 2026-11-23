package com.fintech.onboarding;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TermsPolicy {

    private final String currentVersion;

    public TermsPolicy(
            @Value("${neos.terms.current-version}")
            String currentVersion
    ) {
        this.currentVersion = currentVersion;
    }

    public String requireCurrentVersion(
            String acceptedVersion
    ) {

        if (!currentVersion.equals(acceptedVersion)) {
            throw new UnsupportedTermsVersionException(
                    "Unsupported terms version"
            );
        }

        return currentVersion;
    }

    public String currentVersion() {
        return currentVersion;
    }
}