package com.fintech.backoffice.api;

import com.fintech.backoffice.CustomerDirectoryService;
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
@RequestMapping("/api/backoffice/customers")
public class CustomerDirectoryController {
    private static final Set<String> PARAMETERS = Set.of(
            "search", "accountStatus", "kycStatus", "pageSize", "cursor");
    private final CustomerDirectoryService directory;

    public CustomerDirectoryController(CustomerDirectoryService directory) {
        this.directory = directory;
    }

    @GetMapping
    public ResponseEntity<CustomerDirectoryService.CustomerPage> list(
            @RequestParam MultiValueMap<String, String> parameters) {
        if (!PARAMETERS.containsAll(parameters.keySet()) ||
                parameters.values().stream().anyMatch(values -> values.size() != 1))
            throw new IllegalArgumentException("Invalid directory parameter");
        String size = parameters.getFirst("pageSize");
        int pageSize = 25;
        if (size != null) {
            if (!size.matches("[1-9][0-9]{0,2}"))
                throw new IllegalArgumentException("Invalid pageSize");
            pageSize = Integer.parseInt(size);
        }
        String accountStatus = parameters.getFirst("accountStatus");
        String kycStatus = parameters.getFirst("kycStatus");
        CustomerDirectoryService.Filter filter = new CustomerDirectoryService.Filter(
                parameters.getFirst("search"), accountStatus, kycStatus, pageSize);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(directory.list(filter, parameters.getFirst("cursor")));
    }

    @GetMapping("/{userId}")
    public ResponseEntity<CustomerDirectoryService.CustomerRow> detail(@PathVariable UUID userId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(directory.detail(userId));
    }
}
