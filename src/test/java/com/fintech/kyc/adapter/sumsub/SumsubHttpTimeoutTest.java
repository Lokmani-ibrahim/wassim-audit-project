
package com.fintech.kyc.adapter.sumsub;

import com.fintech.kyc.KycApiErrorCode;
import com.fintech.kyc.KycApiException;
import com.fintech.kyc.port.KycProvider;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SumsubHttpTimeoutTest {

    @Test
    void realReadTimeoutMapsTo503() throws Exception {

        /*
         * A local TCP server accepts connections
         * but never responds to HTTP requests.
         *
         * No external Sumsub network access
         * or real credentials are required.
         */
        try (ServerSocket server = new ServerSocket(
                0,
                1,
                InetAddress.getByName("127.0.0.1")
        )) {

            CountDownLatch accepted =
                    new CountDownLatch(1);

            /*
             * Start a daemon thread to simulate
             * an unresponsive upstream server.
             */
            Thread serverThread =
                    Thread.ofPlatform()
                            .daemon(true)
                            .start(() -> {

                                try (Socket socket =
                                             server.accept()) {

                                    accepted.countDown();

                                    /*
                                     * Hold the connection open
                                     * without sending HTTP data.
                                     */
                                    Thread.sleep(5000);

                                } catch (InterruptedException exception) {

                                    Thread.currentThread()
                                            .interrupt();

                                } catch (IOException ignored) {

                                    /*
                                     * Expected if the server
                                     * is closed during cleanup.
                                     */
                                }
                            });

            try {

                /*
                 * IMPORTANT:
                 *
                 * This is an ordinary Java string,
                 * not a Markdown hyperlink.
                 */
                String baseUrl =
                        "http://127.0.0.1:"
                                + server.getLocalPort();

                SumsubProperties properties =
                        new SumsubProperties(
                                baseUrl,
                                "test-app-token",
                                "test-secret",
                                "id-and-liveness",
                                600
                        );

                /*
                 * Construct the actual timeout-configured
                 * RestClient used by production.
                 *
                 * Connect timeout: 500 ms
                 * Read timeout: 150 ms
                 */
                SumsubHttpClientConfiguration configuration =
                        new SumsubHttpClientConfiguration();

                RestClient restClient =
                        configuration.sumsubRestClient(
                                RestClient.builder(),
                                properties,
                                500,
                                150
                        );

                /*
                 * Use the RestClient constructor,
                 * not the RestClient.Builder constructor.
                 */
                SumsubClient client =
                        new SumsubClient(
                                restClient,
                                properties,
                                JsonMapper.builder().build()
                        );

                SumsubKycProvider provider =
                        new SumsubKycProvider(
                                client,
                                properties
                        );

                UUID userId =
                        UUID.randomUUID();

                long started =
                        System.nanoTime();

                /*
                 * The provider first performs
                 * an applicant lookup using HTTP GET.
                 *
                 * The local server accepts the TCP
                 * connection but never replies.
                 *
                 * The read timeout should cause a
                 * ResourceAccessException internally,
                 * which the provider maps to 503.
                 */
                KycApiException failure =
                        assertThrows(
                                KycApiException.class,
                                () -> provider.createApplicant(
                                        new KycProvider
                                                .CreateApplicantCommand(
                                                userId,
                                                "test@example.com",
                                                "+21612345678"
                                        )
                                )
                        );

                long elapsedMillis =
                        Duration.ofNanos(
                                System.nanoTime() - started
                        ).toMillis();

                /*
                 * Confirm a real TCP connection
                 * was established.
                 */
                assertTrue(
                        accepted.await(
                                1,
                                TimeUnit.SECONDS
                        ),
                        "Local TCP server did not accept a connection"
                );

                /*
                 * Confirm the provider translates
                 * the timeout into the correct
                 * stable application error.
                 */
                assertEquals(
                        KycApiErrorCode.SUMSUB_UNAVAILABLE,
                        failure.getErrorCode()
                );

                assertEquals(
                        503,
                        failure.getStatusCode().value()
                );

                /*
                 * With readTimeout=150ms,
                 * the request should fail well
                 * before the simulated server's
                 * five-second delay.
                 *
                 * Allow extra time for slow CI
                 * and Windows scheduling.
                 */
                assertTrue(
                        elapsedMillis < 3000,
                        "HTTP read timeout was not enforced: "
                                + elapsedMillis
                                + " ms"
                );

            } finally {

                /*
                 * Terminate the local daemon thread
                 * regardless of test outcome.
                 */
                serverThread.interrupt();
                serverThread.join(1000);
            }
        }
    }
}
