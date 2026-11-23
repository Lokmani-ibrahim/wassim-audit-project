package com.fintech.ledger;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(
        HttpStatus.NOT_FOUND
)
public class TransactionHistoryCustomerNotFoundException
        extends RuntimeException {

    public TransactionHistoryCustomerNotFoundException() {
        super(
                "Customer profile not found. Personal onboarding is required."
        );
    }
}