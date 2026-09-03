package io.github.piresrenan.orderhub.customers.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class PostgreSqlCustomerSchemaConstraintsTest {

    private static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse(
                    "postgres:18.6-trixie@sha256:"
                            + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
                    .asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE)
                    .withDatabaseName("orderhub_test")
                    .withUsername("orderhub_test")
                    .withPassword("synthetic-test-password");

    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void migrateAcceptedSchemaChain() {

        var dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword());

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        jdbcTemplate =
                new JdbcTemplate(
                        dataSource);
    }

    @Test
    void v17EstablishesMinimalTenantScopedCustomerRelationships() {

        var tenantA =
                UUID.randomUUID();

        var tenantB =
                UUID.randomUUID();

        var customerA =
                UUID.randomUUID();

        var customerB =
                UUID.randomUUID();

        var sharedCustomer =
                UUID.randomUUID();

        var userA =
                UUID.randomUUID();

        var userB =
                UUID.randomUUID();

        assertAll(
                "V17 Customer persistence contract",

                () ->
                        assertThat(customerTables())
                                .containsExactly(
                                        "customer_account_bindings",
                                        "customer_profiles"),

                () ->
                        assertThat(customerColumns())
                                .containsExactly(
                                        "customer_account_bindings.tenant_id:uuid:NO",
                                        "customer_account_bindings.customer_id:uuid:NO",
                                        "customer_account_bindings.user_id:uuid:NO",
                                        "customer_profiles.tenant_id:uuid:NO",
                                        "customer_profiles.customer_id:uuid:NO"),

                () ->
                        assertThat(customerKeyConstraints())
                                .containsExactly(
                                        "customer_account_bindings:p:tenant_id,customer_id,user_id",
                                        "customer_profiles:p:tenant_id,customer_id"),

                () ->
                        assertThat(customerForeignKeys())
                                .containsExactly(
                                        "customer_account_bindings:tenant_id,customer_id"
                                                + "->customers.customer_profiles:tenant_id,customer_id"),

                () ->
                        assertThat(customerTriggers())
                                .as("V17 must not invent lifecycle triggers")
                                .isEmpty(),

                () -> {
                    insertCustomerProfile(
                            tenantA,
                            customerA);

                    assertThat(bindingCount(
                            tenantA,
                            customerA))
                            .as("CustomerProfile may exist without an authenticated binding")
                            .isZero();
                },

                () ->
                        assertThatThrownBy(() ->
                                insertCustomerBinding(
                                        tenantB,
                                        customerA,
                                        userA))
                                .as("binding requires the CustomerProfile in the same Tenant")
                                .isInstanceOf(
                                        DataIntegrityViolationException.class),

                () -> {
                    insertCustomerProfile(
                            tenantA,
                            customerB);

                    insertCustomerBinding(
                            tenantA,
                            customerA,
                            userA);

                    insertCustomerBinding(
                            tenantA,
                            customerB,
                            userA);

                    insertCustomerBinding(
                            tenantA,
                            customerA,
                            userB);

                    assertThat(bindingCountForUser(
                            tenantA,
                            userA))
                            .as("one User may bind to multiple Customers")
                            .isEqualTo(
                                    2);

                    assertThat(bindingCount(
                            tenantA,
                            customerA))
                            .as("one Customer may bind to multiple Users")
                            .isEqualTo(
                                    2);

                    assertThatThrownBy(() ->
                            insertCustomerBinding(
                                    tenantA,
                                    customerA,
                                    userA))
                            .as("only the exact tuple is unique")
                            .isInstanceOf(
                                    DataIntegrityViolationException.class);
                },

                () -> {
                    insertCustomerProfile(
                            tenantA,
                            sharedCustomer);

                    insertCustomerProfile(
                            tenantB,
                            sharedCustomer);

                    insertCustomerBinding(
                            tenantA,
                            sharedCustomer,
                            userA);

                    insertCustomerBinding(
                            tenantB,
                            sharedCustomer,
                            userA);

                    assertThat(bindingCountAcrossTenants(
                            sharedCustomer,
                            userA))
                            .as("Customer/User relationships remain Tenant-scoped")
                            .isEqualTo(
                                    2);
                });
    }

    private static java.util.List<String> customerTables() {

        return jdbcTemplate.queryForList(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'customers'
                ORDER BY table_name
                """,
                String.class);
    }

    private static java.util.List<String> customerColumns() {

        return jdbcTemplate.queryForList(
                """
                SELECT
                    table_name
                    || '.'
                    || column_name
                    || ':'
                    || data_type
                    || ':'
                    || is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'customers'
                ORDER BY
                    table_name,
                    ordinal_position
                """,
                String.class);
    }

    private static java.util.List<String> customerKeyConstraints() {

        return jdbcTemplate.queryForList(
                """
                SELECT
                    source_table.relname
                    || ':'
                    || CAST(constraint_definition.contype AS text)
                    || ':'
                    || string_agg(
                        source_attribute.attname,
                        ','
                        ORDER BY source_key.ordinality)
                FROM pg_constraint constraint_definition
                JOIN pg_class source_table
                  ON source_table.oid = constraint_definition.conrelid
                JOIN pg_namespace source_namespace
                  ON source_namespace.oid = source_table.relnamespace
                CROSS JOIN LATERAL unnest(
                    constraint_definition.conkey)
                    WITH ORDINALITY source_key(attnum, ordinality)
                JOIN pg_attribute source_attribute
                  ON source_attribute.attrelid = source_table.oid
                 AND source_attribute.attnum = source_key.attnum
                WHERE source_namespace.nspname = 'customers'
                  AND constraint_definition.contype IN ('p', 'u')
                GROUP BY
                    source_table.relname,
                    constraint_definition.contype,
                    constraint_definition.oid
                ORDER BY
                    source_table.relname,
                    constraint_definition.contype,
                    constraint_definition.oid
                """,
                String.class);
    }

    private static java.util.List<String> customerForeignKeys() {

        return jdbcTemplate.queryForList(
                """
                SELECT
                    source_table.relname
                    || ':'
                    || string_agg(
                        source_attribute.attname,
                        ','
                        ORDER BY source_key.ordinality)
                    || '->'
                    || target_namespace.nspname
                    || '.'
                    || target_table.relname
                    || ':'
                    || string_agg(
                        target_attribute.attname,
                        ','
                        ORDER BY source_key.ordinality)
                FROM pg_constraint constraint_definition
                JOIN pg_class source_table
                  ON source_table.oid = constraint_definition.conrelid
                JOIN pg_namespace source_namespace
                  ON source_namespace.oid = source_table.relnamespace
                JOIN pg_class target_table
                  ON target_table.oid = constraint_definition.confrelid
                JOIN pg_namespace target_namespace
                  ON target_namespace.oid = target_table.relnamespace
                CROSS JOIN LATERAL unnest(
                    constraint_definition.conkey)
                    WITH ORDINALITY source_key(attnum, ordinality)
                JOIN LATERAL unnest(
                    constraint_definition.confkey)
                    WITH ORDINALITY target_key(attnum, ordinality)
                  ON target_key.ordinality = source_key.ordinality
                JOIN pg_attribute source_attribute
                  ON source_attribute.attrelid = source_table.oid
                 AND source_attribute.attnum = source_key.attnum
                JOIN pg_attribute target_attribute
                  ON target_attribute.attrelid = target_table.oid
                 AND target_attribute.attnum = target_key.attnum
                WHERE source_namespace.nspname = 'customers'
                  AND constraint_definition.contype = 'f'
                GROUP BY
                    source_table.relname,
                    target_namespace.nspname,
                    target_table.relname,
                    constraint_definition.oid
                ORDER BY
                    source_table.relname,
                    target_namespace.nspname,
                    target_table.relname,
                    constraint_definition.oid
                """,
                String.class);
    }

    private static java.util.List<String> customerTriggers() {

        return jdbcTemplate.queryForList(
                """
                SELECT trigger_name
                FROM information_schema.triggers
                WHERE trigger_schema = 'customers'
                ORDER BY trigger_name
                """,
                String.class);
    }

    private static void insertCustomerProfile(
            UUID tenantId,
            UUID customerId) {

        jdbcTemplate.update(
                """
                INSERT INTO customers.customer_profiles (
                    tenant_id,
                    customer_id
                )
                VALUES (?, ?)
                """,
                tenantId,
                customerId);
    }

    private static void insertCustomerBinding(
            UUID tenantId,
            UUID customerId,
            UUID userId) {

        jdbcTemplate.update(
                """
                INSERT INTO customers.customer_account_bindings (
                    tenant_id,
                    customer_id,
                    user_id
                )
                VALUES (?, ?, ?)
                """,
                tenantId,
                customerId,
                userId);
    }

    private static int bindingCount(
            UUID tenantId,
            UUID customerId) {

        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM customers.customer_account_bindings
                WHERE tenant_id = ?
                  AND customer_id = ?
                """,
                Integer.class,
                tenantId,
                customerId);
    }

    private static int bindingCountForUser(
            UUID tenantId,
            UUID userId) {

        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM customers.customer_account_bindings
                WHERE tenant_id = ?
                  AND user_id = ?
                """,
                Integer.class,
                tenantId,
                userId);
    }

    private static int bindingCountAcrossTenants(
            UUID customerId,
            UUID userId) {

        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM customers.customer_account_bindings
                WHERE customer_id = ?
                  AND user_id = ?
                """,
                Integer.class,
                customerId,
                userId);
    }
}
