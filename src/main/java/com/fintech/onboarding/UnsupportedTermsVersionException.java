package com.fintech.onboarding;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class UnsupportedTermsVersionException
        extends RuntimeException {

    public UnsupportedTermsVersionException(
            String message
    ) {
        super(message);
    }
}