package com.fintech.ledger.adapter.formance;

import com.fintech.ledger.LedgerTransactionRejectedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FormanceClientTest {

    @Test
    void createsTransactionWithReferenceAndIdempotencyKey() {

        RestClient.Builder builder =
                RestClient.builder();

        MockRestServiceServer server =
                MockRestServiceServer
                        .bindTo(builder)
                        .build();

        FormanceProperties properties =
                properties();

        FormanceClient client =
                new FormanceClient(
                        builder
                                .baseUrl(
                                        properties
                                                .baseUrl()
                                                .toString()
                                )
                                .build(),
                        properties
                );

        FormanceCreateTransactionRequest request =
                new FormanceCreateTransactionRequest(
                        List.of(
                                new FormancePosting(
                                        "world",
                                        "users:test:wallet:EUR:available",
                                        "EUR/2",
                                        100
                                )
                        ),
                        Map.of(
                                "neos.operation",
                                "c1_validation_credit"
                        ),
                        "c1-validation-001"
                );

        server.expect(
                        once(),
                        requestTo(
                                "http://localhost:8080"
                                        + "/api/ledger/v2/neos/transactions"
                                        + "?schemaVersion=v1.1.0"
                        )
                )
                .andExpect(
                        method(
                                HttpMethod.POST
                        )
                )
                .andExpect(
                        header(
                                "Idempotency-Key",
                                "idempotency-001"
                        )
                )
                .andExpect(
                        content().json(
                                """
                                {
                                  "postings": [
                                    {
                                      "source": "world",
                                      "destination": "users:test:wallet:EUR:available",
                                      "asset": "EUR/2",
                                      "amount": 100
                                    }
                                  ],
                                  "metadata": {
                                    "neos.operation": "c1_validation_credit"
                                  },
                                  "reference": "c1-validation-001"
                                }
                                """
                        )
                )
                .andRespond(
                        withSuccess(
                                """
                                {
                                  "data": {
                                    "id": 42,
                                    "reference": "c1-validation-001"
                                  }
                                }
                                """,
                                MediaType.APPLICATION_JSON
                        )
                );

        FormanceTransactionResult result =
                client.createTransaction(
                        request,
                        "idempotency-001"
                );

        assertThat(
                result.transactionId()
        ).isEqualTo(42);

        assertThat(
                result.reference()
        ).isEqualTo(
                "c1-validation-001"
        );

        server.verify();
    }

    @Test
    void mapsClientRejectionToTransactionRejectedException() {

        RestClient.Builder builder =
                RestClient.builder();

        MockRestServiceServer server =
                MockRestServiceServer
                        .bindTo(builder)
                        .build();

        FormanceProperties properties =
                properties();

        FormanceClient client =
                new FormanceClient(
                        builder
                                .baseUrl(
                                        properties
                                                .baseUrl()
                                                .toString()
                                )
                                .build(),
                        properties
                );

        FormanceCreateTransactionRequest request =
                new FormanceCreateTransactionRequest(
                        List.of(
                                new FormancePosting(
                                        "world",
                                        "users:test:wallet:EUR:available",
                                        "EUR/2",
                                        100
                                )
                        ),
                        Map.of(),
                        "duplicate-reference"
                );

        server.expect(
                        requestTo(
                                "http://localhost:8080"
                                        + "/api/ledger/v2/neos/transactions"
                                        + "?schemaVersion=v1.1.0"
                        )
                )
                .andRespond(
                        withStatus(
                                HttpStatus.CONFLICT
                        )
                );

        assertThatThrownBy(
                () -> client.createTransaction(
                        request,
                        "another-key"
                )
        )
                .isInstanceOf(
                        LedgerTransactionRejectedException.class
                )
                .hasMessageContaining(
                        "409"
                );

        server.verify();
    }

    private static FormanceProperties properties() {

        return new FormanceProperties(
                URI.create(
                        "http://localhost:8080"
                ),
                "neos",
                "v1.1.0",
                Duration.ofSeconds(2),
                Duration.ofSeconds(5)
        );
    }
    @Test
    void validatesTransactionUsingDryRunAndSchema() {

        RestClient.Builder builder =
                RestClient.builder();

        MockRestServiceServer server =
                MockRestServiceServer
                        .bindTo(builder)
                        .build();

        FormanceProperties properties =
                properties();

        FormanceClient client =
                new FormanceClient(
                        builder
                                .baseUrl(
                                        properties
                                                .baseUrl()
                                                .toString()
                                )
                                .build(),
                        properties
                );

        FormanceCreateTransactionRequest request =
                new FormanceCreateTransactionRequest(
                        List.of(
                                new FormancePosting(
                                        "world",
                                        "users:"
                                                + "550e8400-e29b-41d4-a716-446655440000"
                                                + ":wallet:EUR:available",
                                        "EUR/2",
                                        100
                                )
                        ),
                        Map.of(),
                        "schema-validation-test"
                );

        server.expect(
                        requestTo(
                                "http://localhost:8080"
                                        + "/api/ledger/v2/neos/transactions"
                                        + "?schemaVersion=v1.1.0"
                                        + "&dryRun=true"
                        )
                )
                .andExpect(
                        method(
                                HttpMethod.POST
                        )
                )
                .andRespond(
                        withSuccess(
                                "{}",
                                MediaType.APPLICATION_JSON
                        )
                );

        client.validateTransaction(
                request
        );

        server.verify();
    }
}