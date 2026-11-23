
package com.fintech.kyc.api;

import com.fintech.kyc.SumsubDecisionService;
import com.fintech.kyc.adapter.sumsub.SumsubProperties;
import com.fintech.kyc.adapter.sumsub.SumsubReviewedEvent;
import com.fintech.kyc.adapter.sumsub.SumsubReviewedEventParser;
import com.fintech.kyc.adapter.sumsub.SumsubWebhookSignatureVerifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/webhooks/sumsub")
@ConditionalOnProperty(
        prefix = "neos.kyc.sumsub",
        name = "webhook-enabled",
        havingValue = "true"
)
public class SumsubWebhookController {

    private static final Logger log =
            LoggerFactory.getLogger(
                    SumsubWebhookController.class
            );

    private final SumsubWebhookSignatureVerifier verifier;
    private final SumsubReviewedEventParser parser;
    private final SumsubDecisionService decisionService;

    public SumsubWebhookController(
            @Value("${neos.kyc.sumsub.webhook-secret:}")
            String webhookSecret,

            @Value("${neos.kyc.sumsub.sandbox-mode:true}")
            boolean sandboxMode,

            JsonMapper jsonMapper,
            SumsubProperties properties,
            SumsubDecisionService decisionService
    ) {
        this.verifier =
                new SumsubWebhookSignatureVerifier(
                        webhookSecret
                );

        this.parser = new SumsubReviewedEventParser(
                jsonMapper,
                properties.levelName(),
                sandboxMode
        );

        this.decisionService = decisionService;
    }

    @PostMapping
    public ResponseEntity<Void> receiveWebhook(
            @RequestBody byte[] rawBody,

            @RequestHeader(
                    name = "X-Payload-Digest",
                    required = false
            )
            String digest,

            @RequestHeader(
                    name = "X-Payload-Digest-Alg",
                    required = false
            )
            String algorithm
    ) {

        /*
         * SECURITY:
         *
         * Sumsub is the only supported KYC provider.
         *
         * Verify the webhook signature against
         * the original raw request body.
         *
         * Never trust an unsigned KYC decision.
         */
        if (!verifier.verify(
                rawBody,
                digest,
                algorithm
        )) {
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .build();
        }

        final SumsubReviewedEvent event;

        /*
         * Validate the signed payload before
         * changing customer or KYC state.
         */
        try {
            event = parser.parse(rawBody);

        } catch (RuntimeException exception) {

            log.warn(
                    "Rejected malformed or unsupported Sumsub webhook"
            );

            return ResponseEntity
                    .badRequest()
                    .build();
        }

        /*
         * Apply the verified Sumsub decision.
         *
         * This service is responsible for:
         *
         * - Approval persistence
         * - Rejection persistence
         * - Duplicate webhook protection
         * - Stale decision handling
         * - Eligible account activation
         * - Publishing KycApprovedEvent
         */
        try {

            SumsubDecisionService.Outcome outcome =
                    decisionService.applyVerifiedReview(event);

            if (outcome == null) {
                throw new IllegalStateException(
                        "Missing Sumsub processing outcome"
                );
            }

            /*
             * APPLIED:
             * Decision was persisted successfully.
             *
             * DUPLICATE:
             * Previously processed event.
             *
             * STALE:
             * Obsolete event that must not
             * override the current KYC state.
             *
             * All can be acknowledged safely.
             */
            return ResponseEntity.ok().build();

        } catch (RuntimeException exception) {

            log.error(
                    "Sumsub decision processing failed",
                    exception
            );

            /*
             * Do not acknowledge the webhook
             * when processing has failed.
             *
             * A 503 allows Sumsub to retry
             * according to its delivery policy.
             */
            return ResponseEntity
                    .status(HttpStatus.SERVICE_UNAVAILABLE)
                    .build();
        }
    }
}
