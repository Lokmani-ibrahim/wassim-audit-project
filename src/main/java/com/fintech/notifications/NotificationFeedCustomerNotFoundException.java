package com.fintech.notifications;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(
        HttpStatus.NOT_FOUND
)
public class NotificationFeedCustomerNotFoundException
        extends RuntimeException {

    public NotificationFeedCustomerNotFoundException() {
        super(
                "Customer profile not found. Personal onboarding is required."
        );
    }
}
