package com.fintech.ledger.adapter.formance;

import com.fintech.ledger.LedgerProviderUnavailableException;
import com.fintech.ledger.LedgerTransactionRejectedException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class FormanceWalletQueriesClientTest {

    private static final String BASE =
            "http://localhost:8080/api/ledger/v2/neos";

    private static final String AVAILABLE_PATTERN =
            "users:11111111-2222-3333-4444-555555555555:wallet::available";

    private static final String HOLDS_PATTERN =
            "users:11111111-2222-3333-4444-555555555555:wallet::holds:";

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
    void listsAccountsWithPartialAddressFilter() {

        server.expect(
                        requestTo(BASE + "/accounts?pageSize=100")
                )
                .andExpect(method(HttpMethod.GET))
                .andExpect(
                        content().json(
                                """
                                {
                                  "$match": {
                                    "address": "%s"
                                  }
                                }
                                """.formatted(AVAILABLE_PATTERN)
                        )
                )
                .andRespond(
                        withSuccess(
                                """
                                {
                                  "cursor": {
                                    "pageSize": 100,
                                    "hasMore": false,
                                    "data": [
                                      {
                                        "address": "users:11111111-2222-3333-4444-555555555555:wallet:EUR:available",
                                        "metadata": {
                                          "neos.wallet_status": "ACTIVE"
                                        },
                                        "firstUsage": "2026-11-02T00:08:45.622805Z"
                                      }
                                    ]
                                  }
                                }
                                """,
                                MediaType.APPLICATION_JSON
                        )
                );

        FormanceAccountsResponse response =
                client.getAccounts(
                        Map.of(
                                "$match",
                                Map.of("address", AVAILABLE_PATTERN)
                        ),
                        100,
                        null
                );

        assertEquals(1, response.cursor().data().size());
        assertEquals(
                "ACTIVE",
                response.cursor().data().getFirst()
                        .metadata().get("neos.wallet_status")
        );
        assertFalse(response.cursor().hasMore());

        server.verify();
    }

    @Test
    void readsVolumesWithOrFilterAndKeepsArbitraryPrecision() {

        server.expect(
                        requestTo(BASE + "/volumes?pageSize=100")
                )
                .andExpect(method(HttpMethod.GET))
                .andExpect(
                        content().json(
                                """
                                {
                                  "$or": [
                                    { "$match": { "account": "%s" } },
                                    { "$match": { "account": "%s" } }
                                  ]
                                }
                                """.formatted(
                                        AVAILABLE_PATTERN,
                                        HOLDS_PATTERN
                                )
                        )
                )
                .andRespond(
                        withSuccess(
                                """
                                {
                                  "cursor": {
                                    "pageSize": 100,
                                    "hasMore": false,
                                    "data": [
                                      {
                                        "account": "users:11111111-2222-3333-4444-555555555555:wallet:USD:available",
                                        "asset": "USD/2",
                                        "input": 123456789012345678901234567890,
                                        "output": 0,
                                        "balance": 123456789012345678901234567890
                                      }
                                    ]
                                  }
                                }
                                """,
                                MediaType.APPLICATION_JSON
                        )
                );

        FormanceVolumesResponse response =
                client.getVolumes(
                        Map.of(
                                "$or",
                                List.of(
                                        Map.of(
                                                "$match",
                                                Map.of("account", AVAILABLE_PATTERN)
                                        ),
                                        Map.of(
                                                "$match",
                                                Map.of("account", HOLDS_PATTERN)
                                        )
                                )
                        ),
                        100,
                        null
                );

        assertEquals(
                new BigInteger("123456789012345678901234567890"),
                response.cursor().data().getFirst().balance()
        );

        server.verify();
    }

    @Test
    void followingPageSendsOnlyTheCursor() {

        server.expect(
                        requestTo(BASE + "/volumes?cursor=next-page-token")
                )
                .andExpect(method(HttpMethod.GET))
                .andRespond(
                        withSuccess(
                                """
                                {
                                  "cursor": {
                                    "pageSize": 100,
                                    "hasMore": false,
                                    "data": []
                                  }
                                }
                                """,
                                MediaType.APPLICATION_JSON
                        )
                );

        FormanceVolumesResponse response =
                client.getVolumes(
                        Map.of(),
                        100,
                        "next-page-token"
                );

        assertTrue(response.cursor().data().isEmpty());

        server.verify();
    }

    @Test
    void rejectsMorePagesWithoutNextCursor() {

        server.expect(
                        requestTo(BASE + "/accounts?pageSize=100")
                )
                .andRespond(
                        withSuccess(
                                """
                                {
                                  "cursor": {
                                    "pageSize": 100,
                                    "hasMore": true,
                                    "data": []
                                  }
                                }
                                """,
                                MediaType.APPLICATION_JSON
                        )
                );

        assertThrows(
                LedgerProviderUnavailableException.class,
                () -> client.getAccounts(Map.of(), 100, null)
        );
    }

    @Test
    void rejectsResponseWithoutCursor() {

        server.expect(
                        requestTo(BASE + "/volumes?pageSize=100")
                )
                .andRespond(
                        withSuccess(
                                "{}",
                                MediaType.APPLICATION_JSON
                        )
                );

        assertThrows(
                LedgerProviderUnavailableException.class,
                () -> client.getVolumes(Map.of(), 100, null)
        );
    }

    @Test
    void mapsServerErrorToProviderUnavailable() {

        server.expect(
                        requestTo(BASE + "/volumes?pageSize=100")
                )
                .andRespond(
                        withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                );

        assertThrows(
                LedgerProviderUnavailableException.class,
                () -> client.getVolumes(Map.of(), 100, null)
        );
    }

    @Test
    void mapsClientErrorToRejected() {

        server.expect(
                        requestTo(BASE + "/accounts?pageSize=100")
                )
                .andRespond(
                        withStatus(HttpStatus.BAD_REQUEST)
                );

        assertThrows(
                LedgerTransactionRejectedException.class,
                () -> client.getAccounts(Map.of(), 100, null)
        );
    }

    @Test
    void mapsConnectionFailureToProviderUnavailable() {

        server.expect(
                        requestTo(BASE + "/accounts?pageSize=100")
                )
                .andRespond(
                        request -> {
                            throw new IOException("connection refused");
                        }
                );

        assertThrows(
                LedgerProviderUnavailableException.class,
                () -> client.getAccounts(Map.of(), 100, null)
        );
    }

    @Test
    void rejectsInvalidPageSize() {

        assertThrows(
                IllegalArgumentException.class,
                () -> client.getVolumes(Map.of(), 0, null)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> client.getAccounts(Map.of(), 101, null)
        );
    }
}
