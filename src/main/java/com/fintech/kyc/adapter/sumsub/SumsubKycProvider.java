
package com.fintech.kyc.adapter.sumsub;

import com.fintech.kyc.KycApiErrorCode;
import com.fintech.kyc.KycApiException;
import com.fintech.kyc.port.KycProvider;

import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.core.JacksonException;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
public class SumsubKycProvider implements KycProvider {

    private final SumsubClient client;
    private final SumsubProperties properties;

    public SumsubKycProvider(
            SumsubClient client,
            SumsubProperties properties
    ) {
        this.client = Objects.requireNonNull(client);
        this.properties = Objects.requireNonNull(properties);
    }

    @Override
    public Applicant createApplicant(
            CreateApplicantCommand command
    ) {
        Objects.requireNonNull(command, "command");

        UUID userId = Objects.requireNonNull(
                command.userId(),
                "userId"
        );

        try {

            /*
             * STEP 1 — Reuse an existing applicant.
             *
             * The NEOS user UUID is Sumsub's
             * externalUserId.
             */
            Optional<String> existing =
                    client.findApplicantId(userId);

            if (existing.isPresent()) {
                return new Applicant(
                        requireApplicantId(existing.get())
                );
            }

            /*
             * STEP 2 — Create once.
             *
             * Never automatically retry this POST.
             * A timeout does not guarantee that
             * Sumsub failed to create the applicant.
             */
            try {

                String applicantId =
                        client.createApplicant(
                                userId,
                                command.email(),
                                command.phoneNumber()
                        );

                return new Applicant(
                        requireApplicantId(applicantId)
                );

            } catch (HttpClientErrorException exception) {

                /*
                 * STEP 3 — Recover HTTP 409.
                 *
                 * Another request may have created
                 * the applicant between lookup
                 * and creation.
                 *
                 * Only perform another GET lookup.
                 * Never issue a second creation POST.
                 */
                if (exception.getStatusCode().value() != 409) {
                    throw exception;
                }

                Optional<String> recovered =
                        client.findApplicantId(userId);

                if (recovered.isEmpty()) {
                    throw new KycApiException(
                            KycApiErrorCode.SUMSUB_UPSTREAM_ERROR
                    );
                }

                return new Applicant(
                        requireApplicantId(recovered.get())
                );
            }

        } catch (RestClientException exception) {

            throw translateHttpFailure(exception);

        } catch (JacksonException | IllegalStateException exception) {

            /*
             * A malformed provider response must
             * not be accepted as valid KYC.
             */
            throw new KycApiException(
                    KycApiErrorCode.SUMSUB_UPSTREAM_ERROR
            );
        }
    }

    @Override
    public VerificationSession createVerificationSession(
            CreateVerificationSessionCommand command
    ) {
        Objects.requireNonNull(command, "command");

        UUID userId = Objects.requireNonNull(
                command.userId(),
                "userId"
        );

        try {

            SumsubClient.SdkTokenResponse response =
                    client.generateSdkToken(userId);

            if (response == null
                    || response.token() == null
                    || response.token().isBlank()) {

                throw new KycApiException(
                        KycApiErrorCode.SUMSUB_UPSTREAM_ERROR
                );
            }

            /*
             * Never issue an SDK token associated
             * with another NEOS user.
             */
            if (!userId.toString().equals(response.userId())) {

                throw new KycApiException(
                        KycApiErrorCode.SUMSUB_UPSTREAM_ERROR
                );
            }

            return new VerificationSession(
                    response.token(),
                    properties.sdkTokenTtlSeconds()
            );

        } catch (RestClientException exception) {

            throw translateHttpFailure(exception);

        } catch (JacksonException | IllegalStateException exception) {

            throw new KycApiException(
                    KycApiErrorCode.SUMSUB_UPSTREAM_ERROR
            );
        }
    }

    /*
     * ----------------------------------------------------
     * UPSTREAM FAILURE CLASSIFICATION
     * ----------------------------------------------------
     *
     * 503 — Temporary unavailability:
     *
     * - Network failures
     * - Connection/read timeouts
     * - HTTP 408
     * - HTTP 429
     * - HTTP 5xx
     *
     * 502 — Unexpected upstream responses:
     *
     * - Other HTTP 4xx
     * - Response decoding failures
     * - Unexpected RestClient errors
     *
     * Upstream response bodies and credentials are
     * never copied into public exception messages.
     */
    private KycApiException translateHttpFailure(
            RestClientException exception
    ) {

        if (exception instanceof ResourceAccessException) {

            return new KycApiException(
                    KycApiErrorCode.SUMSUB_UNAVAILABLE
            );
        }

        if (exception instanceof RestClientResponseException response) {

            int status = response.getStatusCode().value();

            if (status == 408
                    || status == 429
                    || status >= 500) {

                return new KycApiException(
                        KycApiErrorCode.SUMSUB_UNAVAILABLE
                );
            }

            return new KycApiException(
                    KycApiErrorCode.SUMSUB_UPSTREAM_ERROR
            );
        }

        return new KycApiException(
                KycApiErrorCode.SUMSUB_UPSTREAM_ERROR
        );
    }

    private String requireApplicantId(
            String applicantId
    ) {

        if (applicantId == null
                || applicantId.isBlank()) {

            throw new KycApiException(
                    KycApiErrorCode.SUMSUB_UPSTREAM_ERROR
            );
        }

        return applicantId;
    }
}
