package com.fintech.identity;

import com.fintech.identity.entity.AccountStatus;
import com.fintech.identity.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Identity-owned, bounded projection for the backoffice read module. */
@Service
public class CustomerDirectoryLookup {
    private final UserRepository users;

    public CustomerDirectoryLookup(UserRepository users) { this.users = users; }

    @Transactional(readOnly = true)
    public List<CustomerDirectoryIdentityRow> scan(UUID after, String accountStatus,
                                                    String email, int limit) {
        if (limit < 1 || limit > 1001) throw new IllegalArgumentException("Invalid scan limit");
        AccountStatus status = accountStatus == null ? null : AccountStatus.valueOf(accountStatus);
        return users.scanDirectory(after, status, email, PageRequest.of(0, limit))
                .stream().map(CustomerDirectoryLookup::row).toList();
    }

    @Transactional(readOnly = true)
    public Optional<CustomerDirectoryIdentityRow> find(UUID userId) {
        return users.findDirectoryRow(userId).map(CustomerDirectoryLookup::row);
    }

    private static CustomerDirectoryIdentityRow row(UserRepository.DirectoryProjection projection) {
        return new CustomerDirectoryIdentityRow(projection.getUserId(), projection.getEmail(),
                projection.getAccountStatus().name(), projection.getCreatedAt());
    }
}
