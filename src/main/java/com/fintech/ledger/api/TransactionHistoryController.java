package com.fintech.ledger.api;

import com.fintech.ledger.CustomerTransactionHistoryService;
import com.fintech.ledger.CustomerTransactionPage;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@RequestMapping("/api/transactions")
public class TransactionHistoryController {

    private final CustomerTransactionHistoryService transactionHistoryService;

    public TransactionHistoryController(
            CustomerTransactionHistoryService transactionHistoryService
    ) {
        this.transactionHistoryService =
                Objects.requireNonNull(
                        transactionHistoryService,
                        "transactionHistoryService must not be null"
                );
    }

    @GetMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    public CustomerTransactionPage history(
            @AuthenticationPrincipal
            Jwt jwt,

            @RequestParam
            LedgerCurrency currency,

            @RequestParam(defaultValue = "20")
            int pageSize,

            @RequestParam(required = false)
            String cursor
    ) {

        return transactionHistoryService.getHistory(
                jwt.getSubject(),
                currency,
                pageSize,
                cursor
        );
    }
}