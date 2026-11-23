
package com.fintech.ledger.fx.demo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

@RestController
@Profile("demo & !prod")
@ConditionalOnProperty(
        prefix = "neos.fx.demo",
        name = "enabled",
        havingValue = "true"
)
@RequestMapping("/api/fx/demo/conversions")
public class FxDemoConversionController {

    private final FxDemoConversionService service;

    public FxDemoConversionController(
            FxDemoConversionService service
    ) {
        this.service = Objects.requireNonNull(service);
    }

    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<FxDemoConversionService.DemoResult> convert(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ConvertRequest request
    ) {

        return ResponseEntity.ok(
                service.convert(
                        jwt.getSubject(),
                        request.quoteId(),
                        request.idempotencyKey()
                )
        );
    }

    /*
     * BUG-FX-3: after a 503, a 409 or a timeout the client does not
     * know the operationId. It can always find its own operation by
     * the idempotency key it sent (404: nothing was claimed).
     */
    @GetMapping(params = "idempotencyKey")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<FxDemoConversionService.DemoResult> findByIdempotencyKey(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam UUID idempotencyKey
    ) {

        return ResponseEntity.ok(
                service.findByRequestKey(
                        jwt.getSubject(),
                        idempotencyKey
                )
        );
    }

    @GetMapping("/{operationId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<FxDemoConversionService.DemoResult> get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID operationId
    ) {

        return ResponseEntity.ok(
                service.get(
                        jwt.getSubject(),
                        operationId
                )
        );
    }

    public record ConvertRequest(
            @NotNull UUID quoteId,
            @NotNull UUID idempotencyKey
    ) {
    }
}
