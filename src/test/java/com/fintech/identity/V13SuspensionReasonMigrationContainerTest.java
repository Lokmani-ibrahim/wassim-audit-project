
package com.fintech.identity;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@EnabledIfEnvironmentVariable(
        named = "RUN_KYC_MIGRATION_CONTAINER_IT",
        matches = "true"
)
class V13SuspensionReasonMigrationContainerTest {

    @Container
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:18-alpine")
                    .withDatabaseName("kyc_migration_it")
                    .withUsername("migration_test")
                    .withPassword("migration_test_only");

    @Test
    void existingSuspendedAccountsRemainRestrictedAfterV13()
            throws Exception {

        /*
         * STEP 1: Create the previous production schema.
         *
         * V13 is intentionally excluded at this stage.
         */
        Flyway.configure()
                .dataSource(
                        postgres.getJdbcUrl(),
                        postgres.getUsername(),
                        postgres.getPassword()
                )
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("12"))
                .load()
                .migrate();

        UUID suspendedId = UUID.randomUUID();
        UUID activeId = UUID.randomUUID();

        /*
         * STEP 2: Insert existing accounts under V12.
         */
        insertLegacyUser(suspendedId, "SUSPENDED");
        insertLegacyUser(activeId, "ACTIVE");

        /*
         * STEP 3: Upgrade the existing database to V13.
         */
        Flyway.configure()
                .dataSource(
                        postgres.getJdbcUrl(),
                        postgres.getUsername(),
                        postgres.getPassword()
                )
                .locations("classpath:db/migration")
                .load()
                .migrate();

        /*
         * STEP 4: Existing suspensions remain blocked.
         *
         * We cannot safely infer why older accounts
         * were suspended, so all are UNSPECIFIED.
         */
        assertEquals(
                "SUSPENDED",
                readAccountStatus(suspendedId)
        );

        assertEquals(
                "UNSPECIFIED",
                readSuspensionReason(suspendedId)
        );

        /*
         * STEP 5: Existing active accounts are unchanged.
         */
        assertEquals(
                "ACTIVE",
                readAccountStatus(activeId)
        );

        assertNull(
                readSuspensionReason(activeId)
        );

        /*
         * STEP 6: Ensure V13 was applied successfully.
         */
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        );
             var statement = connection.prepareStatement(
                     """
                     SELECT COUNT(*)
                     FROM flyway_schema_history
                     WHERE version = '13'
                       AND success = true
                     """
             );
             var result = statement.executeQuery()) {

            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
        }

        /*
         * STEP 7: The database constraint must prevent
         * a suspended account with no reason.
         */
        assertThrows(
                SQLException.class,
                () -> {
                    try (var connection =
                                 DriverManager.getConnection(
                                         postgres.getJdbcUrl(),
                                         postgres.getUsername(),
                                         postgres.getPassword()
                                 );
                         var statement =
                                 connection.prepareStatement(
                                         """
                                         UPDATE users
                                         SET suspension_reason = NULL
                                         WHERE id = ?
                                         """
                                 )) {

                        statement.setObject(1, suspendedId);
                        statement.executeUpdate();
                    }
                }
        );

        /*
         * The rejected SQL update must not modify
         * the original suspended account.
         */
        assertEquals(
                "UNSPECIFIED",
                readSuspensionReason(suspendedId)
        );
    }

    private void insertLegacyUser(
            UUID id,
            String status
    ) throws SQLException {

        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        );
             PreparedStatement statement =
                     connection.prepareStatement(
                             """
                             INSERT INTO users (
                                 id,
                                 keycloak_subject,
                                 email,
                                 account_type,
                                 account_status,
                                 created_at,
                                 updated_at
                             )
                             VALUES (
                                 ?, ?, ?, 'PERSONAL', ?,
                                 CURRENT_TIMESTAMP,
                                 CURRENT_TIMESTAMP
                             )
                             """
                     )) {

            statement.setObject(1, id);
            statement.setString(2, "legacy-" + id);
            statement.setString(3, id + "@example.com");
            statement.setString(4, status);

            assertEquals(1, statement.executeUpdate());
        }
    }

    private String readAccountStatus(UUID id)
            throws SQLException {
        return readUserColumn(
                id,
                "account_status"
        );
    }

    private String readSuspensionReason(UUID id)
            throws SQLException {
        return readUserColumn(
                id,
                "suspension_reason"
        );
    }

    private String readUserColumn(
            UUID id,
            String column
    ) throws SQLException {

        // Column names are fixed internal test constants,
        // never client-controlled SQL.
        if (!"account_status".equals(column)
                && !"suspension_reason".equals(column)) {
            throw new IllegalArgumentException(
                    "Unsupported column"
            );
        }

        String query =
                "SELECT " + column
                        + " FROM users WHERE id = ?";

        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        );
             var statement = connection.prepareStatement(query)) {

            statement.setObject(1, id);

            try (ResultSet result = statement.executeQuery()) {

                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }
}
