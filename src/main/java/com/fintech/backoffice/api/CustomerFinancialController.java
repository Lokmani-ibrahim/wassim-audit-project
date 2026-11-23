package com.fintech.backoffice.api;

import com.fintech.backoffice.CustomerFinancialService;
import com.fintech.ledger.CustomerFinancialRead;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/backoffice/customers/{userId}")
public class CustomerFinancialController {
    private final CustomerFinancialService financial;

    public CustomerFinancialController(CustomerFinancialService financial) {
        this.financial = financial;
    }

    @GetMapping("/wallets")
    public ResponseEntity<CustomerFinancialRead.WalletPage> wallets(@PathVariable UUID userId,
            @RequestParam MultiValueMap<String, String> parameters) {
        if (!parameters.isEmpty()) throw new IllegalArgumentException("Invalid wallet parameters");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(financial.wallets(userId));
    }

    @GetMapping("/transactions")
    public ResponseEntity<CustomerFinancialRead.TransactionPage> transactions(
            @PathVariable UUID userId, @RequestParam MultiValueMap<String, String> parameters) {
        if (!Set.of("currency", "pageSize", "cursor").containsAll(parameters.keySet()) ||
                parameters.values().stream().anyMatch(values -> values.size() != 1))
            throw new IllegalArgumentException("Invalid transaction parameters");
        String size = parameters.getFirst("pageSize");
        if (size != null && !size.matches("[1-9][0-9]{0,2}"))
            throw new IllegalArgumentException("Invalid pageSize");
        int pageSize = size == null ? 20 : Integer.parseInt(size);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(financial.transactions(userId, parameters.getFirst("currency"), pageSize,
                        parameters.getFirst("cursor")));
    }
}
