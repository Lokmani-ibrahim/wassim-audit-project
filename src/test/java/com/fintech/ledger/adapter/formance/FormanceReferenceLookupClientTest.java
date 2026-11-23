
package com.fintech.ledger.adapter.formance;

import com.fintech.ledger.LedgerProviderUnavailableException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class FormanceReferenceLookupClientTest {

    private static final String REFERENCE =
            "internal_transfer:ct_test_001";

    private MockRestServiceServer server;
    private FormanceClient client;

    @BeforeEach
    void setup() {

        RestClient.Builder builder = RestClient.builder();

        server = MockRestServiceServer
                .bindTo(builder)
                .build();

        FormanceProperties properties =
                new FormanceProperties(
                        URI.create("http://localhost:8080"),
                        "neos",
                        "v1.1.0",
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(5)
                );

        client = new FormanceClient(
                builder.baseUrl(
                        properties.baseUrl().toString()
                ).build(),
                properties
        );
    }

    @Test
    void searchesTransactionsUsingExactReference() {

        server.expect(
                        requestTo(
                                "http://localhost:8080"
                                        + "/api/ledger/v2/neos/transactions"
                                        + "?pageSize=25"
                        )
                )
                .andExpect(method(HttpMethod.GET))
                .andExpect(content().json(
                        """
                        {
                          "$match": {
                            "reference": "internal_transfer:ct_test_001"
                          }
                        }
                        """
                ))
                .andRespond(withSuccess(
                        """
                        {
                          "cursor": {
                            "pageSize": 25,
                            "hasMore": false,
                            "data": [
                              {
                                "id": 42,
                                "reference":
                                  "internal_transfer:ct_test_001",
                                "metadata": {
                                  "neos.operation": "internal_transfer",
                                  "neos.operation_id": "ct_test_001",
                                  "neos.currency": "EUR"
                                },
                                "postings": [],
                                "reverted": false
                              }
                            ]
                          }
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));

        var result = client.getTransactionsByReference(
                REFERENCE,
                25,
                null
        );

        assertEquals(1, result.cursor().data().size());
        assertEquals(
                42L,
                result.cursor().data().get(0).id()
        );

        assertEquals(
                REFERENCE,
                result.cursor().data().get(0).reference()
        );

        server.verify();
    }

    @Test
    void nextPageUsesOpaqueCursorOnly() {

        server.expect(
                        requestTo(
                                "http://localhost:8080"
                                        + "/api/ledger/v2/neos/transactions"
                                        + "?cursor=cursor-page-2"
                        )
                )
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        """
                        {
                          "cursor": {
                            "pageSize": 25,
                            "hasMore": false,
                            "data": []
                          }
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));

        var result = client.getTransactionsByReference(
                REFERENCE,
                25,
                "cursor-page-2"
        );

        assertTrue(result.cursor().data().isEmpty());
        assertFalse(result.cursor().hasMore());

        server.verify();
    }

    @Test
    void malformedResponseFailsClosed() {

        server.expect(
                requestTo(
                        "http://localhost:8080"
                                + "/api/ledger/v2/neos/transactions"
                                + "?pageSize=25"
                )
        ).andRespond(withSuccess(
                """
                {
                  "cursor": {
                    "pageSize": 25,
                    "hasMore": true,
                    "data": []
                  }
                }
                """,
                MediaType.APPLICATION_JSON
        ));

        assertThrows(
                LedgerProviderUnavailableException.class,
                () -> client.getTransactionsByReference(
                        REFERENCE,
                        25,
                        null
                )
        );

        server.verify();
    }

    @Test
    void invalidPageSizeIsRejected() {

        assertThrows(
                IllegalArgumentException.class,
                () -> client.getTransactionsByReference(
                        REFERENCE,
                        0,
                        null
                )
        );
    }
}
