package com.fintech.ledger;

import com.fintech.identity.IdentityLookupService;
import com.fintech.ledger.adapter.formance.FormanceClient;
import com.fintech.ledger.adapter.formance.FormanceTransactionData;
import com.fintech.ledger.adapter.formance.FormanceTransactionPosting;
import com.fintech.ledger.adapter.formance.FormanceTransactionsResponse;
import com.fintech.ledger.internal.taxonomy.LedgerAccountAddress;
import com.fintech.ledger.internal.taxonomy.LedgerAccountAddressFactory;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class CustomerTransactionHistoryService {

    private final IdentityLookupService identityLookupService;
    private final FormanceClient formanceClient;

    public CustomerTransactionHistoryService(
            IdentityLookupService identityLookupService,
            FormanceClient formanceClient
    ) {

        this.identityLookupService =
                Objects.requireNonNull(
                        identityLookupService,
                        "identityLookupService must not be null"
                );

        this.formanceClient =
                Objects.requireNonNull(
                        formanceClient,
                        "formanceClient must not be null"
                );
    }

    public CustomerTransactionPage getHistory(
            String keycloakSubject,
            LedgerCurrency currency,
            int pageSize,
            String cursor
    ) {

        Objects.requireNonNull(
                keycloakSubject,
                "keycloakSubject must not be null"
        );

        Objects.requireNonNull(
                currency,
                "currency must not be null"
        );

        if (keycloakSubject.isBlank()) {
            throw new IllegalArgumentException(
                    "keycloakSubject must not be blank"
            );
        }

        if (pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException(
                    "pageSize must be between 1 and 100"
            );
        }

        IdentityLookupService.IdentityProfile profile =
                identityLookupService
                        .findProfileByKeycloakSubject(
                                keycloakSubject
                        )
                        .orElseThrow(
                                TransactionHistoryCustomerNotFoundException::new
                        );

        return getHistoryForUser(profile.userId(), currency, pageSize, cursor);
    }

    /** Staff callers must resolve and authorize the selected customer first. */
    public CustomerTransactionPage getHistoryForUser(
            UUID userId, LedgerCurrency currency, int pageSize, String cursor
    ) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (pageSize < 1 || pageSize > 100)
            throw new IllegalArgumentException("pageSize must be between 1 and 100");
        LedgerAccountAddress customerAccount =
                LedgerAccountAddressFactory
                        .customerAvailable(
                                userId,
                                currency
                        );

        FormanceTransactionsResponse response =
                formanceClient
                        .getTransactionsForAccount(
                                customerAccount.value(),
                                pageSize,
                                cursor
                        );

        Map<String, FormanceTransactionData> settlements = settledPayouts(
                response.cursor().data(), userId, currency);

        List<CustomerTransactionItem> items =
                response
                        .cursor()
                        .data()
                        .stream()
                        .map(
                                transaction ->
                                        mapTransaction(
                                                transaction,
                                                userId,
                                                customerAccount.value(),
                                                currency,
                                                settlements
                                        )
                        )
                        .flatMap(
                                Optional::stream
                        )
                        .toList();

        return new CustomerTransactionPage(
                items,
                response.cursor().hasMore(),
                response.cursor().next()
        );
    }

    private Map<String, FormanceTransactionData> settledPayouts(
            List<FormanceTransactionData> page, UUID userId, LedgerCurrency currency) {
        Map<String, String> holds = new HashMap<>();
        for (FormanceTransactionData transaction : page) {
            Map<String, String> metadata = transaction.metadata();
            if (metadata == null || !"payout_reserve".equals(metadata.get("neos.operation")))
                continue;
            String operationId = metadata.get("neos.operation_id");
            if (operationId == null || operationId.isBlank()) continue;
            holds.put(operationId, LedgerAccountAddressFactory
                    .customerHold(userId, currency, operationId).value());
        }
        if (holds.isEmpty()) return Map.of();
        List<String> accounts = new ArrayList<>(holds.values());
        Map<String, FormanceTransactionData> settlements = new HashMap<>();
        String cursor = null;
        for (int pageNumber = 0; pageNumber < 10; pageNumber++) {
            FormanceTransactionsResponse response = formanceClient.getTransactionsForAccounts(
                    accounts, 100, cursor);
            for (FormanceTransactionData transaction : response.cursor().data()) {
                Map<String, String> metadata = transaction.metadata();
                if (metadata == null || !"payout_settle".equals(metadata.get("neos.operation")))
                    continue;
                String operationId = metadata.get("neos.operation_id");
                String account = holds.get(operationId);
                if (account != null && transaction.postings() != null &&
                        transaction.postings().stream().anyMatch(posting -> account.equals(posting.source())))
                    settlements.putIfAbsent(operationId, transaction);
            }
            if (!response.cursor().hasMore()) return settlements;
            cursor = response.cursor().next();
        }
        throw new LedgerProviderUnavailableException("Payout settlement lookup exceeded bounded pages");
    }

    private Optional<CustomerTransactionItem> mapTransaction(
            FormanceTransactionData transaction,
            UUID userId,
            String customerAccount,
            LedgerCurrency currency,
            Map<String, FormanceTransactionData> settlements
    ){

        Map<String, String> metadata =
                transaction.metadata() == null
                        ? Map.of()
                        : transaction.metadata();

        String operation =
                metadata.get(
                        "neos.operation"
                );

        if (operation == null
                || operation.isBlank()) {

            /*
             * Transactions without Neos business metadata
             * are not part of the public customer history.
             *
             * Example:
             * old integration-test seed transactions.
             */
            return Optional.empty();
        }

        return switch (operation) {

            /*
             * A real incoming payment:
             *
             * world -> bank
             * control -> customer
             *
             * Customer sees only:
             *
             * + amount
             */
            case "incoming_payment" ->
                    creditFromDestination(
                            transaction,
                            customerAccount,
                            currency,
                            CustomerTransactionType.INCOMING_PAYMENT
                    );

            /*
             * An incoming payment initially went to suspense,
             * then was attributed to the customer.
             *
             * For the customer this is still simply an
             * incoming payment.
             */
            case "incoming_attribution" ->
                    creditFromDestination(
                            transaction,
                            customerAccount,
                            currency,
                            CustomerTransactionType.INCOMING_PAYMENT
                    );

            /*
             * Same Formance transaction has different meaning
             * depending on which customer is viewing it.
             *
             * Sender:
             *   DEBIT
             *
             * Recipient:
             *   CREDIT
             */
            case "internal_transfer" ->
                    transfer(
                            transaction,
                            customerAccount,
                            currency
                    );

            /*
             * customer -> platform:fees
             */
            case "fee_collection" ->
                    debitFromSource(
                            transaction,
                            customerAccount,
                            currency,
                            CustomerTransactionType.FEE
                    );

            /*
             * These are internal payout state movements:
             *
             * available -> hold
             * hold -> available
             *
             * They must NOT appear as separate customer
             * transactions.
             *
             * Payout settlement will be added as the next
             * history slice because settlement occurs from
             * the hold account rather than available.
             */
            case "payout_reserve" ->
                    payoutFromReservation(
                            transaction,
                            userId,
                            currency,
                            settlements
                    );

            case "payout_release",
                 "payout_settle" ->
                    Optional.empty();

            /*
             * FX conversion (demo settlement, two Formance
             * transactions per operation):
             *
             * fx_reserve: source available -> source FX hold
             *   the money leaves the source wallet: DEBIT.
             *
             * fx_convert: four postings, of which only
             *   control:{DST} -> destination available
             *   touches a customer available account: CREDIT.
             *
             * Each wallet's history therefore shows exactly one
             * CONVERSION item per operation.
             */
            case "fx_reserve" ->
                    debitFromSource(
                            transaction,
                            customerAccount,
                            currency,
                            CustomerTransactionType.CONVERSION
                    );

            case "fx_convert" ->
                    creditFromDestination(
                            transaction,
                            customerAccount,
                            currency,
                            CustomerTransactionType.CONVERSION
                    );

            default ->
                    Optional.empty();
        };
    }
    private Optional<CustomerTransactionItem> payoutFromReservation(
            FormanceTransactionData reserveTransaction,
            UUID userId,
            LedgerCurrency currency,
            Map<String, FormanceTransactionData> settlements
    ) {

        Map<String, String> metadata =
                reserveTransaction.metadata() == null
                        ? Map.of()
                        : reserveTransaction.metadata();

        String operationId =
                metadata.get(
                        "neos.operation_id"
                );

        if (operationId == null
                || operationId.isBlank()) {

            return Optional.empty();
        }

        /*
         * Every payout gets its own deterministic hold account:
         *
         * users:{userId}:wallet:{currency}:holds:{operationId}
         */
        LedgerAccountAddress holdAccount =
                LedgerAccountAddressFactory
                        .customerHold(
                                userId,
                                currency,
                                operationId
                        );

        /*
         * A unique payout hold normally has:
         *
         * reserve:
         * available -> hold
         *
         * then exactly one of:
         *
         * settle:
         * hold -> control
         *
         * release:
         * hold -> available
         *
         * pageSize 10 is intentionally generous for this
         * operation-specific account.
         */
        FormanceTransactionData settled = settlements.get(operationId);
        if (settled == null) return Optional.empty();
        return java.util.stream.Stream.of(settled)
                .filter(
                        transaction -> {

                            Map<String, String> transactionMetadata =
                                    transaction.metadata() == null
                                            ? Map.of()
                                            : transaction.metadata();

                            return "payout_settle".equals(
                                    transactionMetadata.get(
                                            "neos.operation"
                                    )
                            )
                                    && operationId.equals(
                                    transactionMetadata.get(
                                            "neos.operation_id"
                                    )
                            );
                        }
                )
                .findFirst()
                .flatMap(
                        settlement ->
                                settlement
                                        .postings()
                                        .stream()
                                        .filter(
                                                posting ->
                                                        holdAccount
                                                                .value()
                                                                .equals(
                                                                        posting.source()
                                                                )
                                        )
                                        .findFirst()
                                        .map(
                                                posting ->
                                                        item(
                                                                settlement,
                                                                CustomerTransactionType.PAYOUT,
                                                                CustomerTransactionDirection.DEBIT,
                                                                currency,
                                                                posting.amount()
                                                        )
                                        )
                );
    }

    private Optional<CustomerTransactionItem> creditFromDestination(
            FormanceTransactionData transaction,
            String customerAccount,
            LedgerCurrency currency,
            CustomerTransactionType type
    ) {

        return transaction
                .postings()
                .stream()
                .filter(
                        posting ->
                                customerAccount.equals(
                                        posting.destination()
                                )
                )
                .findFirst()
                .map(
                        posting ->
                                item(
                                        transaction,
                                        type,
                                        CustomerTransactionDirection.CREDIT,
                                        currency,
                                        posting.amount()
                                )
                );
    }

    private Optional<CustomerTransactionItem> debitFromSource(
            FormanceTransactionData transaction,
            String customerAccount,
            LedgerCurrency currency,
            CustomerTransactionType type
    ) {

        return transaction
                .postings()
                .stream()
                .filter(
                        posting ->
                                customerAccount.equals(
                                        posting.source()
                                )
                )
                .findFirst()
                .map(
                        posting ->
                                item(
                                        transaction,
                                        type,
                                        CustomerTransactionDirection.DEBIT,
                                        currency,
                                        posting.amount()
                                )
                );
    }

    private Optional<CustomerTransactionItem> transfer(
            FormanceTransactionData transaction,
            String customerAccount,
            LedgerCurrency currency
    ) {

        Optional<FormanceTransactionPosting> outgoing =
                transaction
                        .postings()
                        .stream()
                        .filter(
                                posting ->
                                        customerAccount.equals(
                                                posting.source()
                                        )
                        )
                        .findFirst();

        if (outgoing.isPresent()) {

            return Optional.of(
                    item(
                            transaction,
                            CustomerTransactionType.TRANSFER,
                            CustomerTransactionDirection.DEBIT,
                            currency,
                            outgoing.get().amount()
                    )
            );
        }

        Optional<FormanceTransactionPosting> incoming =
                transaction
                        .postings()
                        .stream()
                        .filter(
                                posting ->
                                        customerAccount.equals(
                                                posting.destination()
                                        )
                        )
                        .findFirst();

        return incoming.map(
                posting ->
                        item(
                                transaction,
                                CustomerTransactionType.TRANSFER,
                                CustomerTransactionDirection.CREDIT,
                                currency,
                                posting.amount()
                        )
        );
    }

    private CustomerTransactionItem item(
            FormanceTransactionData transaction,
            CustomerTransactionType type,
            CustomerTransactionDirection direction,
            LedgerCurrency currency,
            long amountMinor
    ) {

        CustomerTransactionStatus status =
                Boolean.TRUE.equals(transaction.reverted())
                        ? CustomerTransactionStatus.REVERSED
                        : CustomerTransactionStatus.COMPLETED;

        return new CustomerTransactionItem(
                transaction.id(),
                type,
                direction,
                currency,
                amountMinor,
                status,
                transaction.reference(),
                transaction.timestamp()
        );
    }
}
