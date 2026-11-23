package com.fintech.ledger.api;

import com.fintech.ledger.CustomerWalletService;
import com.fintech.ledger.CustomerWallets;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@RequestMapping("/api/wallets")
public class CustomerWalletController {

    private final CustomerWalletService walletService;

    public CustomerWalletController(
            CustomerWalletService walletService
    ) {
        this.walletService =
                Objects.requireNonNull(
                        walletService,
                        "walletService must not be null"
                );
    }

    @GetMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<CustomerWallets> wallets(
            @AuthenticationPrincipal
            Jwt jwt
    ) {

        return ResponseEntity.ok(
                walletService.getWallets(
                        jwt.getSubject()
                )
        );
    }
}
