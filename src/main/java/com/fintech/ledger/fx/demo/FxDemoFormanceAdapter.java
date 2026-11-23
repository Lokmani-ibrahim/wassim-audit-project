
package com.fintech.ledger.fx.demo;

import com.fintech.ledger.adapter.formance.FormanceClient;
import com.fintech.ledger.adapter.formance.FormanceCreateScriptTransactionRequest;
import com.fintech.ledger.adapter.formance.FormanceCreateTransactionRequest;
import com.fintech.ledger.adapter.formance.FormanceFxConversionEvidenceVerifier.ExpectedFxSettlement;
import com.fintech.ledger.adapter.formance.FormancePosting;
import com.fintech.ledger.adapter.formance.FormanceScript;
import com.fintech.ledger.adapter.formance.FormanceTransactionPosting;
import com.fintech.ledger.adapter.formance.FormanceTransactionResult;
import com.fintech.ledger.internal.taxonomy.LedgerAccountAddressFactory;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
@Profile("demo & !prod")
public class FxDemoFormanceAdapter {

    private final FormanceClient client;

    public FxDemoFormanceAdapter(FormanceClient client) {
        this.client = Objects.requireNonNull(client);
    }

    /*
     * Reservation is a real Formance transaction.
     *
     * Customer EUR available -> customer EUR FX hold.
     *
     * No mock ledger is involved.
     */
    public FormanceTransactionResult reserve(
            ExpectedFxSettlement expected
    ) {

        String source =
                LedgerAccountAddressFactory.customerAvailable(
                        expected.userId(),
                        expected.sourceCurrency()
                ).value();

        String destination =
                LedgerAccountAddressFactory.customerHold(
                        expected.userId(),
                        expected.sourceCurrency(),
                        expected.holdOperationId()
                ).value();

        String reference = reserveReference(expected);

        FormanceCreateTransactionRequest request =
                new FormanceCreateTransactionRequest(
                        List.of(
                                new FormancePosting(
                                        source,
                                        destination,
                                        expected.sourceCurrency()
                                                .formanceAsset(),
                                        expected.sourceMinor()
                                )
                        ),
                        Map.of(
                                "neos.operation", "fx_reserve",
                                "neos.operation_id",
                                expected.operationId().toString(),
                                "neos.user_id",
                                expected.userId().toString(),
                                "neos.currency",
                                expected.sourceCurrency().currencyCode(),
                                "neos.fx_simulated", "true"
                        ),
                        reference
                );

        return client.createTransaction(
                request,
                reference
        );
    }

    /*
     * Settlement is ONE atomic Formance transaction
     * containing the four canonical FX postings.
     *
     * The sponsor-bank execution is SIMULATED.
     * This must run only on a disposable demo ledger.
     */
    public FormanceTransactionResult settle(
            ExpectedFxSettlement expected
    ) {

        List<FormanceTransactionPosting> postings =
                expected.expectedPostings();

        StringBuilder script = new StringBuilder();

        for (int i = 0; i < postings.size(); i++) {

            FormanceTransactionPosting posting =
                    postings.get(i);

            /*
             * The destination-currency control account
             * represents a liability and is permitted
             * to move further negative.
             *
             * No customer or bank-master account is
             * granted an overdraft.
             */
            String overdraft =
                    i == 3
                            ? " allowing unbounded overdraft"
                            : "";

            script.append(
                    """
                    send [%s %d] (
                      source = @%s%s
                      destination = @%s
                    )

                    """.formatted(
                            posting.asset(),
                            posting.amount(),
                            posting.source(),
                            overdraft,
                            posting.destination()
                    )
            );
        }

        Map<String, String> metadata = new HashMap<>(
                expected.expectedMetadata()
        );

        metadata.put(
                "neos.fx_rate",
                expected.bankRate().toPlainString()
        );

        metadata.put("neos.fx_simulated", "true");
        metadata.put("neos.fx_demo", "true");

        FormanceCreateScriptTransactionRequest request =
                new FormanceCreateScriptTransactionRequest(
                        new FormanceScript(script.toString()),
                        Map.copyOf(metadata),
                        expected.reference()
                );

        return client.createScriptTransaction(
                request,
                expected.reference()
        );
    }

    public static String reserveReference(
            ExpectedFxSettlement expected
    ) {

        return "fx_reserve:"
                + expected.operationId()
                .toString()
                .replace("-", "");
    }
}