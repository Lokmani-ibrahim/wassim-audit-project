package com.fintech.notifications;

import com.fintech.identity.entity.UserEntity;
import com.fintech.identity.repository.UserRepository;
import com.fintech.notifications.entity.NotificationOutboxEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "neos.notifications.microsoft-graph.enabled=false",
        "neos.notifications.dispatcher.enabled=false"
})
@AutoConfigureMockMvc
class NotificationFeedControllerIntegrationTest {

    private static final String PATH = "/api/notifications";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NotificationOutboxService outboxService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    /*
     * Outbox rows created here use @neos.local recipients.
     * They are deleted after each test so that they can never
     * be sent if Graph and the dispatcher are enabled later
     * against the same database (EMAIL-1).
     */
    private final List<UUID> createdUsers =
            new ArrayList<>();

    @AfterEach
    void deleteOutboxRows() {

        for (UUID userId : createdUsers) {
            jdbcTemplate.update(
                    "DELETE FROM notification_outbox WHERE user_id = ?",
                    userId
            );
        }
    }

    @Test
    void customerReadsOwnNotificationsNewestFirst() throws Exception {

        String subject = onboard();
        UUID userId = userId(subject);

        long creditTx = transactionId();
        long transferTx = transactionId();

        UUID credit = enqueue(
                userId,
                creditTx,
                TransactionNotificationType.INCOMING_PAYMENT,
                TransactionNotificationDirection.CREDIT,
                "EUR",
                "EUR/2",
                2_500L,
                "incoming_payment:feed-" + creditTx
        );

        UUID transfer = enqueue(
                userId,
                transferTx,
                TransactionNotificationType.TRANSFER,
                TransactionNotificationDirection.DEBIT,
                "TND",
                "TND/3",
                1_500L,
                "internal_transfer:feed-" + transferTx
        );

        setCreatedAt(credit, Instant.parse("2026-11-05T03:00:00Z"));
        setCreatedAt(transfer, Instant.parse("2026-11-05T04:00:00Z"));

        mockMvc.perform(get(PATH).with(customer(subject)))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Cache-Control",
                        containsString("no-store")
                ))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()))

                .andExpect(jsonPath("$.items[0].id").value(transfer.toString()))
                .andExpect(jsonPath("$.items[0].type").value("TRANSFER"))
                .andExpect(jsonPath("$.items[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.items[0].currency").value("TND"))
                .andExpect(jsonPath("$.items[0].amountMinor").value(1_500))
                .andExpect(jsonPath("$.items[0].transactionId").value(transferTx))
                .andExpect(jsonPath("$.items[0].reference")
                        .value("internal_transfer:feed-" + transferTx))
                .andExpect(jsonPath("$.items[0].testCredit").value(false))
                .andExpect(jsonPath("$.items[0].createdAt")
                        .value(containsString("2026-11-05T04:00")))

                .andExpect(jsonPath("$.items[1].id").value(credit.toString()))
                .andExpect(jsonPath("$.items[1].type").value("INCOMING_PAYMENT"))
                .andExpect(jsonPath("$.items[1].testCredit").value(true));
    }

    @Test
    void internalFieldsAreNeverExposed() throws Exception {

        String subject = onboard();
        UUID userId = userId(subject);

        UUID row = enqueueTransfer(userId);

        jdbcTemplate.update(
                """
                UPDATE notification_outbox
                SET last_error = 'provider said no'
                WHERE id = ?
                """,
                row
        );

        MvcResult result =
                mockMvc.perform(get(PATH).with(customer(subject)))
                        .andExpect(status().isOk())
                        .andReturn();

        String body =
                result.getResponse().getContentAsString();

        JsonNode item =
                jsonMapper.readTree(body).get("items").get(0);

        Set<String> fields =
                new HashSet<>(item.propertyNames());

        assertThat(fields)
                .containsExactlyInAnyOrder(
                        "id",
                        "type",
                        "direction",
                        "currency",
                        "amountMinor",
                        "transactionId",
                        "reference",
                        "testCredit",
                        "createdAt"
                );

        assertThat(body)
                .doesNotContain("@neos.local")
                .doesNotContain(userId.toString())
                .doesNotContain("TRANSACTION_COMPLETED")
                .doesNotContain("PENDING")
                .doesNotContain("provider said no")
                .doesNotContain("TND/3");
    }

    @Test
    void rowsAreListedWhateverTheirEmailStatus() throws Exception {

        String subject = onboard();
        UUID userId = userId(subject);

        UUID deadLetter = enqueueTransfer(userId);
        UUID accepted = enqueueTransfer(userId);

        setStatus(deadLetter, "DEAD_LETTER");
        setStatus(accepted, "PROVIDER_ACCEPTED");

        mockMvc.perform(get(PATH).with(customer(subject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)));
    }

    @Test
    void customersNeverSeeEachOthersNotifications() throws Exception {

        String senderSubject = onboard();
        String recipientSubject = onboard();

        UUID sender = userId(senderSubject);
        UUID recipient = userId(recipientSubject);

        long tx = transactionId();

        // Same ledger transaction, one row per side
        // (InternalTransferService).
        UUID debit = enqueue(
                sender,
                tx,
                TransactionNotificationType.TRANSFER,
                TransactionNotificationDirection.DEBIT,
                "EUR",
                "EUR/2",
                100L,
                "internal_transfer:feed-" + tx
        );

        UUID creditRow = enqueue(
                recipient,
                tx,
                TransactionNotificationType.TRANSFER,
                TransactionNotificationDirection.CREDIT,
                "EUR",
                "EUR/2",
                100L,
                "internal_transfer:feed-" + tx
        );

        enqueueTransfer(sender);

        mockMvc.perform(get(PATH).with(customer(senderSubject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[?(@.id == '" + creditRow + "')]")
                        .isEmpty())
                .andExpect(jsonPath("$.items[?(@.id == '" + debit + "')].direction")
                        .value("DEBIT"));

        mockMvc.perform(get(PATH).with(customer(recipientSubject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(creditRow.toString()))
                .andExpect(jsonPath("$.items[0].direction").value("CREDIT"));
    }

    @Test
    void anotherCustomersCursorOnlyPagesOwnRows() throws Exception {

        String ownerSubject = onboard();
        String otherSubject = onboard();

        UUID owner = userId(ownerSubject);
        UUID other = userId(otherSubject);

        for (int i = 0; i < 3; i++) {
            enqueueTransfer(owner);
        }

        UUID otherRow = enqueueTransfer(other);

        // Older than every owner row, so it lies "after"
        // any owner cursor.
        setCreatedAt(otherRow, Instant.parse("2020-01-01T00:00:00Z"));

        String ownerCursor =
                jsonMapper.readTree(
                        mockMvc.perform(
                                        get(PATH)
                                                .param("pageSize", "1")
                                                .with(customer(ownerSubject))
                                )
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString()
                ).get("nextCursor").asString();

        mockMvc.perform(
                        get(PATH)
                                .param("cursor", ownerCursor)
                                .with(customer(otherSubject))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(otherRow.toString()));
    }

    @Test
    void cursorPagingIsCompleteAndStableWithTies() throws Exception {

        String subject = onboard();
        UUID userId = userId(subject);

        Set<UUID> expected = new HashSet<>();

        for (int i = 0; i < 5; i++) {
            expected.add(enqueueTransfer(userId));
        }

        // Three rows share one timestamp: the id breaks ties.
        Instant tie = Instant.parse("2026-11-05T05:00:00Z");
        int index = 0;
        for (UUID id : expected) {
            setCreatedAt(
                    id,
                    index < 3
                            ? tie
                            : tie.minusSeconds(60L * index)
            );
            index++;
        }

        List<UUID> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;

        do {

            var request =
                    get(PATH)
                            .param("pageSize", "2")
                            .with(customer(subject));

            if (cursor != null) {
                request.param("cursor", cursor);
            }

            JsonNode page =
                    jsonMapper.readTree(
                            mockMvc.perform(request)
                                    .andExpect(status().isOk())
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsString()
                    );

            for (JsonNode item : page.get("items")) {
                seen.add(UUID.fromString(item.get("id").asString()));
            }

            cursor =
                    page.get("hasMore").asBoolean()
                            ? page.get("nextCursor").asString()
                            : null;

            pages++;

        } while (cursor != null && pages < 10);

        assertThat(pages).isEqualTo(3);
        assertThat(seen).hasSize(5).doesNotHaveDuplicates();
        assertThat(new HashSet<>(seen)).isEqualTo(expected);
    }

    @Test
    void unreadableRowsAreSkippedWithoutBreakingThePage() throws Exception {

        String subject = onboard();
        UUID userId = userId(subject);

        UUID good = enqueueTransfer(userId);
        UUID corrupt = enqueueTransfer(userId);
        UUID unknownType = enqueueTransfer(userId);

        jdbcTemplate.update(
                "UPDATE notification_outbox SET payload_json = ? WHERE id = ?",
                "{\"not\":\"a payload\"}",
                corrupt
        );

        jdbcTemplate.update(
                """
                UPDATE notification_outbox
                SET payload_json = replace(payload_json, '"TRANSFER"', '"SOMETHING_NEW"')
                WHERE id = ?
                """,
                unknownType
        );

        mockMvc.perform(get(PATH).with(customer(subject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(good.toString()));
    }

    @Test
    void customerWithNoNotificationsGetsAnEmptyPage() throws Exception {

        String subject = onboard();
        userId(subject);

        mockMvc.perform(get(PATH).with(customer(subject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    @Test
    void invalidPageSizeOrCursorIsBadRequest() throws Exception {

        String subject = onboard();
        userId(subject);

        for (String pageSize : List.of("0", "101", "-1", "abc")) {
            mockMvc.perform(
                            get(PATH)
                                    .param("pageSize", pageSize)
                                    .with(customer(subject))
                    )
                    .andExpect(status().isBadRequest());
        }

        mockMvc.perform(
                        get(PATH)
                                .param("cursor", "not-a-cursor")
                                .with(customer(subject))
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void customerWithoutProfileGetsNotFound() throws Exception {

        mockMvc.perform(
                        get(PATH).with(
                                customer("not-onboarded-" + UUID.randomUUID())
                        )
                )
                .andExpect(status().isNotFound());
    }

    @Test
    void complianceOfficerCannotReadCustomerNotifications() throws Exception {

        mockMvc.perform(
                        get(PATH).with(
                                jwt()
                                        .jwt(token -> token.subject("feed-compliance"))
                                        .authorities(
                                                new SimpleGrantedAuthority(
                                                        "ROLE_COMPLIANCE_OFFICER"
                                                )
                                        )
                        )
                )
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedCallerCannotReadNotifications() throws Exception {

        mockMvc.perform(get(PATH))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void readingTheFeedDoesNotChangeOutboxRows() throws Exception {

        String subject = onboard();
        UUID userId = userId(subject);

        UUID row = enqueueTransfer(userId);

        String before = rowState(row);

        mockMvc.perform(get(PATH).with(customer(subject)))
                .andExpect(status().isOk());

        assertThat(rowState(row)).isEqualTo(before);
        assertThat(before).startsWith("PENDING|0|");
    }

    private UUID enqueueTransfer(UUID userId) {

        long tx = transactionId();

        return enqueue(
                userId,
                tx,
                TransactionNotificationType.TRANSFER,
                TransactionNotificationDirection.DEBIT,
                "TND",
                "TND/3",
                1_500L,
                "internal_transfer:feed-" + tx
        );
    }

    private UUID enqueue(
            UUID userId,
            long transactionId,
            TransactionNotificationType type,
            TransactionNotificationDirection direction,
            String currency,
            String asset,
            long amountMinor,
            String reference
    ) {

        NotificationOutboxEntity entity =
                outboxService.enqueueTransactionEmail(
                        "feed." + userId + "@neos.local",
                        new TransactionNotificationPayload(
                                userId,
                                transactionId,
                                type,
                                direction,
                                currency,
                                asset,
                                amountMinor,
                                reference,
                                Instant.now()
                        )
                );

        return entity.getId();
    }

    private void setCreatedAt(UUID rowId, Instant createdAt) {

        jdbcTemplate.update(
                "UPDATE notification_outbox SET created_at = ? WHERE id = ?",
                Timestamp.from(createdAt),
                rowId
        );
    }

    private void setStatus(UUID rowId, String status) {

        jdbcTemplate.update(
                "UPDATE notification_outbox SET status = ? WHERE id = ?",
                status,
                rowId
        );
    }

    private String rowState(UUID rowId) {

        return jdbcTemplate.queryForObject(
                """
                SELECT status || '|' || attempt_count || '|' || updated_at
                FROM notification_outbox
                WHERE id = ?
                """,
                String.class,
                rowId
        );
    }

    private static long transactionId() {

        return ThreadLocalRandom.current().nextLong(
                1_000_000_000L,
                9_000_000_000L
        );
    }

    private String onboard() throws Exception {

        String suffix =
                UUID.randomUUID().toString().substring(0, 12);

        String subject =
                "feed-" + suffix;

        mockMvc.perform(
                        post("/api/onboarding/personal")
                                .with(
                                        jwt()
                                                .jwt(token -> token
                                                        .subject(subject)
                                                        .claim(
                                                                "email",
                                                                "feed." + suffix + "@neos.local"
                                                        )
                                                )
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                "ROLE_CUSTOMER"
                                                        )
                                                )
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "phoneNumber": null,
                                          "installationId": "android-feed-%s",
                                          "platform": "ANDROID",
                                          "termsVersion": "1.0"
                                        }
                                        """.formatted(suffix)
                                )
                )
                .andExpect(status().isOk());

        return subject;
    }

    private UUID userId(String subject) {

        UUID userId =
                userRepository
                        .findByKeycloakSubject(subject)
                        .map(UserEntity::getId)
                        .orElseThrow();

        if (!createdUsers.contains(userId)) {
            createdUsers.add(userId);
        }

        return userId;
    }

    private static RequestPostProcessor customer(String subject) {

        return jwt()
                .jwt(token -> token.subject(subject))
                .authorities(
                        new SimpleGrantedAuthority("ROLE_CUSTOMER")
                );
    }
}
