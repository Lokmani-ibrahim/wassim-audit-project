package com.fintech.ledger.adapter.formance;

import com.fintech.ledger.LedgerProviderUnavailableException;
import com.fintech.ledger.LedgerTransactionRejectedException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;


import java.math.BigInteger;
import java.util.Map;
import java.util.Objects;

@Component
public class FormanceClient {

    private static final String IDEMPOTENCY_HEADER =
            "Idempotency-Key";

    private final RestClient restClient;
    private final FormanceProperties properties;

    public FormanceClient(
            @Qualifier("formanceRestClient")
            RestClient restClient,
            FormanceProperties properties
    ) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public void verifyConnection() {

        try {

            restClient
                    .get()
                    .uri(
                            "/api/ledger/v2/{ledger}",
                            properties.ledgerName()
                    )
                    .retrieve()
                    .toBodilessEntity();

        } catch (RestClientResponseException exception) {

            throw mapHttpException(
                    "verify Formance ledger connection",
                    exception
            );

        } catch (RestClientException exception) {

            throw new LedgerProviderUnavailableException(
                    "Unable to connect to Formance ledger '%s'"
                            .formatted(
                                    properties.ledgerName()
                            ),
                    exception
            );
        }
    }

    public void addAccountMetadata(
            String address,
            Map<String, String> metadata,
            String idempotencyKey
    ) {

        String requiredAddress =
                requireText(
                        address,
                        "address"
                );

        String requiredIdempotencyKey =
                requireText(
                        idempotencyKey,
                        "idempotencyKey"
                );

        Objects.requireNonNull(
                metadata,
                "metadata must not be null"
        );

        if (metadata.isEmpty()) {

            throw new IllegalArgumentException(
                    "metadata must not be empty"
            );
        }

        Map<String, String> immutableMetadata =
                Map.copyOf(
                        metadata
                );

        try {

            restClient
                    .post()
                    .uri(
                            "/api/ledger/v2/{ledger}/accounts/{address}/metadata"
                                    + "?schemaVersion={schemaVersion}",
                            properties.ledgerName(),
                            requiredAddress,
                            properties.schemaVersion()
                    )
                    .header(
                            IDEMPOTENCY_HEADER,
                            requiredIdempotencyKey
                    )
                    .contentType(
                            MediaType.APPLICATION_JSON
                    )
                    .body(
                            immutableMetadata
                    )
                    .retrieve()
                    .toBodilessEntity();

        } catch (RestClientResponseException exception) {

            throw mapHttpException(
                    "add Formance account metadata",
                    exception
            );

        } catch (RestClientException exception) {

            throw new LedgerProviderUnavailableException(
                    "Unable to provision Formance account '%s' in ledger '%s'"
                            .formatted(
                                    requiredAddress,
                                    properties.ledgerName()
                            ),
                    exception
            );
        }
    }

    public FormanceAccountData getAccount(
            String address
    ) {

        String requiredAddress =
                requireText(
                        address,
                        "address"
                );

        try {

            FormanceAccountResponse response =
                    restClient
                            .get()
                            .uri(
                                    "/api/ledger/v2/{ledger}/accounts/{address}",
                                    properties.ledgerName(),
                                    requiredAddress
                            )
                            .retrieve()
                            .body(
                                    FormanceAccountResponse.class
                            );

            if (response == null
                    || response.data() == null) {

                throw new LedgerProviderUnavailableException(
                        "Formance returned an empty account response"
                );
            }

            return response.data();

        } catch (RestClientResponseException exception) {

            throw mapHttpException(
                    "read Formance account",
                    exception
            );

        } catch (RestClientException exception) {

            throw new LedgerProviderUnavailableException(
                    "Unable to read Formance account '%s' from ledger '%s'"
                            .formatted(
                                    requiredAddress,
                                    properties.ledgerName()
                            ),
                    exception
            );
        }
    }

    public BigInteger getAccountBalance(
            String address,
            String asset
    ) {

        String requiredAddress =
                requireText(
                        address,
                        "address"
                );

        String requiredAsset =
                requireText(
                        asset,
                        "asset"
                );

        /*
         * Ledger V2 exposes balances through the /volumes endpoint.
         *
         * Our current Formance deployment accepts "account"
         * as a query filter key, but not "asset".
         *
         * Therefore:
         *
         * 1. filter by account in Formance
         * 2. select the asset in Java
         */
        Map<String, Object> filter =
                Map.of(
                        "$match",
                        Map.of(
                                "account",
                                requiredAddress
                        )
                );

        try {

            FormanceVolumesResponse response =
                    restClient
                            .method(
                                    HttpMethod.GET
                            )
                            .uri(
                                    "/api/ledger/v2/{ledger}/volumes"
                                            + "?pageSize=15",
                                    properties.ledgerName()
                            )
                            .contentType(
                                    MediaType.APPLICATION_JSON
                            )
                            .body(
                                    filter
                            )
                            .retrieve()
                            .body(
                                    FormanceVolumesResponse.class
                            );

            if (response == null
                    || response.cursor() == null
                    || response.cursor().data() == null) {

                throw new LedgerProviderUnavailableException(
                        "Formance returned an invalid volumes response"
                );
            }

            return response
                    .cursor()
                    .data()
                    .stream()
                    .filter(
                            volume ->
                                    requiredAddress.equals(
                                            volume.account()
                                    )
                                            && requiredAsset.equals(
                                            volume.asset()
                                    )
                    )
                    .findFirst()
                    .map(
                            FormanceVolumeBalance::balance
                    )
                    .orElse(
                            BigInteger.ZERO
                    );

        } catch (RestClientResponseException exception) {

            throw mapHttpException(
                    "read Formance account balance",
                    exception
            );

        } catch (RestClientException exception) {

            throw new LedgerProviderUnavailableException(
                    "Unable to read balance for account '%s' and asset '%s' from Formance ledger '%s'"
                            .formatted(
                                    requiredAddress,
                                    requiredAsset,
                                    properties.ledgerName()
                            ),
                    exception
            );
        }
    }
    public FormanceTransactionsResponse getTransactionsForAccount(
            String account,
            int pageSize,
            String cursor
    ) {

        String requiredAccount =
                requireText(
                        account,
                        "account"
                );

        if (pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException(
                    "pageSize must be between 1 and 100"
            );
        }

        try {

            FormanceTransactionsResponse response;

            /*
             * Formance cursors are opaque and already contain:
             *
             * - original filter
             * - page size
             * - pagination position
             *
             * Therefore subsequent calls send ONLY the cursor.
             */
            if (cursor != null
                    && !cursor.isBlank()) {

                response =
                        restClient
                                .get()
                                .uri(
                                        "/api/ledger/v2/{ledger}/transactions"
                                                + "?cursor={cursor}",
                                        properties.ledgerName(),
                                        cursor.trim()
                                )
                                .retrieve()
                                .body(
                                        FormanceTransactionsResponse.class
                                );

            } else {

                /*
                 * $match is an EXACT match.
                 *
                 * Example:
                 *
                 * users:{userId}:wallet:EUR:available
                 */
                Map<String, Object> filter =
                        Map.of(
                                "$match",
                                Map.of(
                                        "account",
                                        requiredAccount
                                )
                        );

                response =
                        restClient
                                .method(
                                        HttpMethod.GET
                                )
                                .uri(
                                        "/api/ledger/v2/{ledger}/transactions"
                                                + "?pageSize={pageSize}",
                                        properties.ledgerName(),
                                        pageSize
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .body(
                                        filter
                                )
                                .retrieve()
                                .body(
                                        FormanceTransactionsResponse.class
                                );
            }

            if (response == null
                    || response.cursor() == null
                    || response.cursor().data() == null) {

                throw new LedgerProviderUnavailableException(
                        "Formance returned an invalid transactions response"
                );
            }

            return response;

        } catch (RestClientResponseException exception) {

            throw mapHttpException(
                    "read Formance transaction history",
                    exception
            );

        } catch (RestClientException exception) {

            throw new LedgerProviderUnavailableException(
                    "Unable to read transaction history for account '%s' from Formance ledger '%s'"
                            .formatted(
                                    requiredAccount,
                                    properties.ledgerName()
                            ),
                    exception
            );
        }
    }

    /** One bounded query for the hold accounts referenced by a history page. */
    public FormanceTransactionsResponse getTransactionsForAccounts(
            java.util.List<String> accounts, int pageSize, String cursor
    ) {
        if (accounts == null || accounts.isEmpty() || accounts.size() > 100 ||
                accounts.stream().anyMatch(value -> value == null || value.isBlank()) ||
                pageSize < 1 || pageSize > 100)
            throw new IllegalArgumentException("Invalid transaction account batch");
        try {
            FormanceTransactionsResponse response;
            if (cursor != null) {
                response = restClient.get()
                        .uri("/api/ledger/v2/{ledger}/transactions?cursor={cursor}",
                                properties.ledgerName(), cursor)
                        .retrieve().body(FormanceTransactionsResponse.class);
            } else {
                java.util.Map<String, Object> filter = java.util.Map.of("$or",
                        accounts.stream().map(account -> java.util.Map.of(
                                "$match", java.util.Map.of("account", account))).toList());
                response = restClient.method(HttpMethod.GET)
                        .uri("/api/ledger/v2/{ledger}/transactions?pageSize={pageSize}",
                                properties.ledgerName(), pageSize)
                        .contentType(MediaType.APPLICATION_JSON).body(filter)
                        .retrieve().body(FormanceTransactionsResponse.class);
            }
            if (response == null || response.cursor() == null || response.cursor().data() == null ||
                    (response.cursor().hasMore() && (response.cursor().next() == null ||
                            response.cursor().next().isBlank())))
                throw new LedgerProviderUnavailableException("Invalid Formance transaction batch response");
            return response;
        } catch (RestClientResponseException exception) {
            throw mapHttpException("read Formance transaction batch", exception);
        } catch (RestClientException exception) {
            throw new LedgerProviderUnavailableException("Unable to read Formance transaction batch", exception);
        }
    }

    /*
     * Lists accounts with their metadata.
     *
     * The filter uses the Formance v2 query language.
     * An empty address segment is a wildcard and the
     * segment count must match, for example:
     *
     * users:{userId}:wallet::available
     *
     * Verified against Formance ledger v2.4.14.
     */
    public FormanceAccountsResponse getAccounts(
            Map<String, Object> filter,
            int pageSize,
            String cursor
    ) {

        Objects.requireNonNull(
                filter,
                "filter must not be null"
        );

        if (pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException(
                    "pageSize must be between 1 and 100"
            );
        }

        try {

            FormanceAccountsResponse response;

            if (cursor != null
                    && !cursor.isBlank()) {

                // Subsequent pages must use only the
                // opaque cursor, not the original filter.
                response =
                        restClient
                                .get()
                                .uri(
                                        "/api/ledger/v2/{ledger}/accounts"
                                                + "?cursor={cursor}",
                                        properties.ledgerName(),
                                        cursor.trim()
                                )
                                .retrieve()
                                .body(
                                        FormanceAccountsResponse.class
                                );

            } else {

                response =
                        restClient
                                .method(
                                        HttpMethod.GET
                                )
                                .uri(
                                        "/api/ledger/v2/{ledger}/accounts"
                                                + "?pageSize={pageSize}",
                                        properties.ledgerName(),
                                        pageSize
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .body(
                                        filter
                                )
                                .retrieve()
                                .body(
                                        FormanceAccountsResponse.class
                                );
            }

            if (response == null
                    || response.cursor() == null
                    || response.cursor().data() == null) {

                throw new LedgerProviderUnavailableException(
                        "Formance returned an invalid accounts response"
                );
            }

            if (response.cursor().hasMore()
                    && (response.cursor().next() == null
                    || response.cursor().next().isBlank())) {

                throw new LedgerProviderUnavailableException(
                        "Formance accounts response has "
                                + "more pages but no next cursor"
                );
            }

            return response;

        } catch (RestClientResponseException exception) {

            throw mapHttpException(
                    "list Formance accounts",
                    exception
            );

        } catch (RestClientException exception) {

            throw new LedgerProviderUnavailableException(
                    "Unable to list accounts from Formance ledger '%s'"
                            .formatted(
                                    properties.ledgerName()
                            ),
                    exception
            );
        }
    }

    /*
     * Lists balances (one row per account and asset) for
     * every account matching the filter.
     *
     * Accounts without any movement are not returned.
     */
    public FormanceVolumesResponse getVolumes(
            Map<String, Object> filter,
            int pageSize,
            String cursor
    ) {

        Objects.requireNonNull(
                filter,
                "filter must not be null"
        );

        if (pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException(
                    "pageSize must be between 1 and 100"
            );
        }

        try {

            FormanceVolumesResponse response;

            if (cursor != null
                    && !cursor.isBlank()) {

                // Subsequent pages must use only the
                // opaque cursor, not the original filter.
                response =
                        restClient
                                .get()
                                .uri(
                                        "/api/ledger/v2/{ledger}/volumes"
                                                + "?cursor={cursor}",
                                        properties.ledgerName(),
                                        cursor.trim()
                                )
                                .retrieve()
                                .body(
                                        FormanceVolumesResponse.class
                                );

            } else {

                response =
                        restClient
                                .method(
                                        HttpMethod.GET
                                )
                                .uri(
                                        "/api/ledger/v2/{ledger}/volumes"
                                                + "?pageSize={pageSize}",
                                        properties.ledgerName(),
                                        pageSize
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .body(
                                        filter
                                )
                                .retrieve()
                                .body(
                                        FormanceVolumesResponse.class
                                );
            }

            if (response == null
                    || response.cursor() == null
                    || response.cursor().data() == null) {

                throw new LedgerProviderUnavailableException(
                        "Formance returned an invalid volumes response"
                );
            }

            if (response.cursor().hasMore()
                    && (response.cursor().next() == null
                    || response.cursor().next().isBlank())) {

                throw new LedgerProviderUnavailableException(
                        "Formance volumes response has "
                                + "more pages but no next cursor"
                );
            }

            return response;

        } catch (RestClientResponseException exception) {

            throw mapHttpException(
                    "read Formance volumes",
                    exception
            );

        } catch (RestClientException exception) {

            throw new LedgerProviderUnavailableException(
                    "Unable to read volumes from Formance ledger '%s'"
                            .formatted(
                                    properties.ledgerName()
                            ),
                    exception
            );
        }
    }

    public FormanceTransactionResult createTransaction(
            FormanceCreateTransactionRequest request,
            String idempotencyKey
    ) {

        Objects.requireNonNull(
                request,
                "request must not be null"
        );

        return submitTransaction(
                request,
                idempotencyKey,
                "create Formance transaction"
        );
    }

    public FormanceTransactionResult createScriptTransaction(
            FormanceCreateScriptTransactionRequest request,
            String idempotencyKey
    ) {

        Objects.requireNonNull(
                request,
                "request must not be null"
        );

        return submitTransaction(
                request,
                idempotencyKey,
                "create Formance script transaction"
        );
    }

    public void validateTransaction(
            FormanceCreateTransactionRequest request
    ) {

        Objects.requireNonNull(
                request,
                "request must not be null"
        );

        try {

            restClient
                    .post()
                    .uri(
                            "/api/ledger/v2/{ledger}/transactions"
                                    + "?schemaVersion={schemaVersion}"
                                    + "&dryRun=true",
                            properties.ledgerName(),
                            properties.schemaVersion()
                    )
                    .header(
                            HttpHeaders.CONTENT_TYPE,
                            "application/json"
                    )
                    .body(
                            request
                    )
                    .retrieve()
                    .toBodilessEntity();

        } catch (RestClientResponseException exception) {

            throw mapHttpException(
                    "validate Formance transaction",
                    exception
            );

        } catch (RestClientException exception) {

            throw new LedgerProviderUnavailableException(
                    "Unable to validate transaction against Formance ledger '%s'"
                            .formatted(
                                    properties.ledgerName()
                            ),
                    exception
            );
        }
    }

    private FormanceTransactionResult submitTransaction(
            Object request,
            String idempotencyKey,
            String operation
    ) {

        String requiredIdempotencyKey =
                requireText(
                        idempotencyKey,
                        "idempotencyKey"
                );

        try {

            FormanceTransactionResponse response =
                    restClient
                            .post()
                            .uri(
                                    "/api/ledger/v2/{ledger}/transactions"
                                            + "?schemaVersion={schemaVersion}",
                                    properties.ledgerName(),
                                    properties.schemaVersion()
                            )
                            .header(
                                    IDEMPOTENCY_HEADER,
                                    requiredIdempotencyKey
                            )
                            .header(
                                    HttpHeaders.CONTENT_TYPE,
                                    "application/json"
                            )
                            .body(
                                    request
                            )
                            .retrieve()
                            .body(
                                    FormanceTransactionResponse.class
                            );

            if (response == null
                    || response.data() == null) {

                throw new LedgerProviderUnavailableException(
                        "Formance returned an empty transaction response"
                );
            }

            return new FormanceTransactionResult(
                    response.data().id(),
                    response.data().reference()
            );

        } catch (RestClientResponseException exception) {

            throw mapHttpException(
                    operation,
                    exception
            );

        } catch (RestClientException exception) {

            throw new LedgerProviderUnavailableException(
                    "Unable to %s in Formance ledger '%s'"
                            .formatted(
                                    operation,
                                    properties.ledgerName()
                            ),
                    exception
            );
        }
    }

    private RuntimeException mapHttpException(
            String operation,
            RestClientResponseException exception
    ) {

        int status =
                exception
                        .getStatusCode()
                        .value();

        String message =
                "Failed to %s for ledger '%s': HTTP %d"
                        .formatted(
                                operation,
                                properties.ledgerName(),
                                status
                        );

        if (exception
                .getStatusCode()
                .is5xxServerError()) {

            return new LedgerProviderUnavailableException(
                    message,
                    exception
            );
        }

        return new LedgerTransactionRejectedException(
                message,
                exception
        );
    }

    private static String requireText(
            String value,
            String fieldName
    ) {

        Objects.requireNonNull(
                value,
                fieldName + " must not be null"
        );

        String trimmed =
                value.trim();

        if (trimmed.isEmpty()) {

            throw new IllegalArgumentException(
                    fieldName
                            + " must not be blank"
            );
        }

        return trimmed;
    }

    public FormanceTransactionsResponse getTransactionsByReference(
            String reference,
            int pageSize,
            String cursor
    ) {
        String requiredReference = requireText(
                reference,
                "reference"
        );

        if (pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException(
                    "pageSize must be between 1 and 100"
            );
        }

        try {
            FormanceTransactionsResponse response;

            if (cursor != null && !cursor.isBlank()) {

                // Subsequent pages must use only the
                // opaque cursor, not the original filter.
                response = restClient
                        .get()
                        .uri(
                                "/api/ledger/v2/{ledger}/transactions"
                                        + "?cursor={cursor}",
                                properties.ledgerName(),
                                cursor.trim()
                        )
                        .retrieve()
                        .body(FormanceTransactionsResponse.class);

            } else {

                // Exact business-reference filter.
                Map<String, Object> filter = Map.of(
                        "$match",
                        Map.of("reference", requiredReference)
                );

                response = restClient
                        .method(HttpMethod.GET)
                        .uri(
                                "/api/ledger/v2/{ledger}/transactions"
                                        + "?pageSize={pageSize}",
                                properties.ledgerName(),
                                pageSize
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(filter)
                        .retrieve()
                        .body(FormanceTransactionsResponse.class);
            }

            if (response == null
                    || response.cursor() == null
                    || response.cursor().data() == null) {

                throw new LedgerProviderUnavailableException(
                        "Formance returned an invalid "
                                + "transaction lookup response"
                );
            }

            if (response.cursor().hasMore()
                    && (response.cursor().next() == null
                    || response.cursor().next().isBlank())) {

                throw new LedgerProviderUnavailableException(
                        "Formance transaction lookup has "
                                + "more pages but no next cursor"
                );
            }

            return response;

        } catch (RestClientResponseException exception) {

            throw mapHttpException(
                    "read Formance transaction by reference",
                    exception
            );

        } catch (RestClientException exception) {

            throw new LedgerProviderUnavailableException(
                    "Unable to read Formance transaction "
                            + "reference from ledger '%s'"
                            .formatted(properties.ledgerName()),
                    exception
            );
        }
    }

}
