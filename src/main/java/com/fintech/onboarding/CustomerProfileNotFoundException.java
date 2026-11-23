package com.fintech.onboarding;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class CustomerProfileNotFoundException extends RuntimeException {

    public CustomerProfileNotFoundException() {
        super("Customer profile not found. Personal onboarding is required.");
    }
}
