package com.fintech.backoffice;

import com.fintech.identity.CustomerDirectoryIdentityRow;
import com.fintech.identity.CustomerDirectoryLookup;
import com.fintech.kyc.KycStatusLookup;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class CustomerDirectoryService {
    private static final int MAX_SCAN = 1000;
    private final CustomerDirectoryLookup users;
    private final KycStatusLookup kyc;

    public CustomerDirectoryService(CustomerDirectoryLookup users, KycStatusLookup kyc) {
        this.users = users;
        this.kyc = kyc;
    }

    public record CustomerRow(UUID userId, String email, String accountStatus,
                              String kycStatus, Instant createdAt) {}
    public record CustomerPage(List<CustomerRow> items, String nextCursor) {}

    public record Filter(String search, String accountStatus, String kycStatus, int pageSize) {
        public Filter {
            if (pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("Invalid pageSize");
            search = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
            if (search.length() > 320 || search.contains("\n") || search.contains("\r"))
                throw new IllegalArgumentException("Invalid search");
            if (!search.isEmpty() && !isUuid(search) &&
                    !search.matches("[^\\s@|]+@[^\\s@|]+"))
                throw new IllegalArgumentException("Search must be an exact email or user ID");
            if (accountStatus != null && !List.of("PENDING_KYC", "ACTIVE", "SUSPENDED", "CLOSED")
                    .contains(accountStatus)) throw new IllegalArgumentException("Invalid accountStatus");
            if (kycStatus != null && !kycStatus.equals("MISSING") &&
                    !List.of("NOT_STARTED", "PENDING", "IN_REVIEW", "APPROVED",
                            "REJECTED", "RESUBMISSION_REQUIRED").contains(kycStatus))
                throw new IllegalArgumentException("Invalid kycStatus");
        }

        private static boolean isUuid(String input) {
            try { return UUID.fromString(input).toString().equals(input); }
            catch (IllegalArgumentException ex) { return false; }
        }
    }

    @Transactional(readOnly = true)
    public CustomerPage list(Filter filter, String cursor) {
        UUID after = parseCursor(filter, cursor);
        if (!filter.search().isEmpty() && Filter.isUuid(filter.search())) {
            if (after != null) throw new IllegalArgumentException("Cursor is invalid for ID search");
            return users.find(UUID.fromString(filter.search()))
                    .filter(row -> filter.accountStatus() == null
                            || row.accountStatus().equals(filter.accountStatus()))
                    .map(row -> {
                        String status = kyc.findStatuses(List.of(row.userId())).get(row.userId());
                        return matchesKyc(filter.kycStatus(), status)
                                ? new CustomerPage(List.of(toRow(row, status)), null)
                                : new CustomerPage(List.of(), null);
                    }).orElseGet(() -> new CustomerPage(List.of(), null));
        }

        int scanLimit = filter.kycStatus() == null ? filter.pageSize() + 1 : MAX_SCAN + 1;
        String email = filter.search().isEmpty() ? null : filter.search();
        List<CustomerDirectoryIdentityRow> candidates = users.scan(
                after, filter.accountStatus(), email, scanLimit);
        int checked = Math.min(candidates.size(), filter.kycStatus() == null
                ? filter.pageSize() : MAX_SCAN);
        List<CustomerDirectoryIdentityRow> batch = candidates.subList(0, checked);
        Map<UUID, String> statuses = kyc.findStatuses(batch.stream()
                .map(CustomerDirectoryIdentityRow::userId).toList());
        List<CustomerRow> items = new ArrayList<>();
        UUID last = after;
        for (CustomerDirectoryIdentityRow candidate : batch) {
            last = candidate.userId();
            String status = statuses.get(last);
            if (matchesKyc(filter.kycStatus(), status)) items.add(toRow(candidate, status));
            if (items.size() == filter.pageSize()) break;
        }
        boolean more = candidates.size() > checked ||
                (last != null && !batch.isEmpty() && !last.equals(batch.getLast().userId()));
        return new CustomerPage(List.copyOf(items), more ? encodeCursor(filter, last) : null);
    }

    @Transactional(readOnly = true)
    public CustomerRow detail(UUID userId) {
        CustomerDirectoryIdentityRow row = users.find(userId)
                .orElseThrow(CustomerNotFoundException::new);
        return toRow(row, kyc.findStatuses(List.of(userId)).get(userId));
    }

    private static CustomerRow toRow(CustomerDirectoryIdentityRow row, String status) {
        return new CustomerRow(row.userId(), row.email(), row.accountStatus(),
                status, row.createdAt());
    }

    private static boolean matchesKyc(String filter, String actual) {
        return filter == null || (filter.equals("MISSING") ? actual == null : filter.equals(actual));
    }

    private static String encodeCursor(Filter filter, UUID last) {
        String value = String.join("\n", "1", filter.search(),
                filter.accountStatus() == null ? "" : filter.accountStatus(),
                filter.kycStatus() == null ? "" : filter.kycStatus(),
                Integer.toString(filter.pageSize()), last.toString());
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static UUID parseCursor(Filter filter, String cursor) {
        if (cursor == null) return null;
        if (cursor.length() > 1024 || !cursor.matches("[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("Invalid cursor");
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor),
                    StandardCharsets.UTF_8).split("\n", -1);
            if (parts.length != 6 || !parts[0].equals("1") ||
                    !parts[1].equals(filter.search()) ||
                    !parts[2].equals(filter.accountStatus() == null ? "" : filter.accountStatus()) ||
                    !parts[3].equals(filter.kycStatus() == null ? "" : filter.kycStatus()) ||
                    !parts[4].equals(Integer.toString(filter.pageSize())) ||
                    !UUID.fromString(parts[5]).toString().equals(parts[5]))
                throw new IllegalArgumentException("Cursor does not match filters");
            return UUID.fromString(parts[5]);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid cursor", ex);
        }
    }
}
