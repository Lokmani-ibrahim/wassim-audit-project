package com.fintech.ledger.fx.api;

import com.fintech.ledger.fx.FxQuoteService;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/fx/quotes")
public class FxQuoteController {

    private final FxQuoteService quoteService;

    public FxQuoteController(
            FxQuoteService quoteService
    ) {
        this.quoteService =
                Objects.requireNonNull(quoteService);
    }

    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<FxQuoteResponse> createQuote(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody FxQuoteRequest request
    ) {

        return ResponseEntity.ok(
                quoteService.quote(
                        jwt.getSubject(),
                        request
                )
        );
    }

    @GetMapping("/{quoteId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<FxQuoteResponse> getQuote(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID quoteId
    ) {

        return ResponseEntity.ok(
                quoteService.getQuote(
                        jwt.getSubject(),
                        quoteId
                )
        );
    }
}