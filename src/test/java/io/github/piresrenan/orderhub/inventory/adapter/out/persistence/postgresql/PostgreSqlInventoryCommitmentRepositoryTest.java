package io.github.piresrenan.orderhub.inventory.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryCommitmentRepository;
import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryPersistenceException;
import io.github.piresrenan.orderhub.inventory.domain.model.InventoryCommitment;

@Testcontainers
class PostgreSqlInventoryCommitmentRepositoryTest {

    private static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse(
                    "postgres:18.6-trixie@sha256:"
                            + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
                    .asCompatibleSubstituteFor("postgres");

    private static final UUID TENANT_A =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111");

    private static final UUID TENANT_B =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222");

    private static final UUID VARIANT_A =
            UUID.fromString(
                    "33333333-3333-3333-3333-333333333333");

    private static final UUID VARIANT_B =
            UUID.fromString(
                    "44444444-4444-4444-4444-444444444444");

    private static final UUID ORDER_ID =
            UUID.fromString(
                    "55555555-5555-5555-5555-555555555555");

    private static final UUID OTHER_ORDER_ID =
            UUID.fromString(
                    "66666666-6666-6666-6666-666666666666");

    private static final UUID COMMITMENT_A =
            UUID.fromString(
                    "77777777-7777-7777-7777-777777777777");

    private static final UUID COMMITMENT_B =
            UUID.fromString(
                    "88888888-8888-8888-8888-888888888888");

    private static final Instant CREATED_AT =
            Instant.parse(
                    "2026-09-01T10:11:12.123456Z");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE)
                    .withDatabaseName("orderhub_test")
                    .withUsername("orderhub_test")
                    .withPassword("synthetic-test-password");

    private static JdbcTemplate jdbcTemplate;

    private static InventoryCommitmentRepository repository;

    @BeforeAll
    static void migrateSchemaAndCreateRepository() {

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
                new JdbcTemplate(dataSource);

        repository =
                new PostgreSqlInventoryCommitmentRepository(
                        jdbcTemplate);
    }

    @BeforeEach
    void cleanCommitments() {

        jdbcTemplate.update("""
                TRUNCATE TABLE
                    inventory.inventory_commitments
                """);
    }

    @Test
    void savesAndRehydratesFullyAllocatedCommitment() {

        var commitment =
                commitment(
                        COMMITMENT_A,
                        TENANT_A,
                        ORDER_ID,
                        VARIANT_A,
                        5,
                        5,
                        0,
                        CREATED_AT);

        assertThat(
                repository.save(
                        commitment))
                .isSameAs(
                        commitment);

        var persisted =
                repository.findByOrderAndVariant(
                                TENANT_A,
                                ORDER_ID,
                                VARIANT_A)
                        .orElseThrow();

        assertCommitment(
                persisted,
                COMMITMENT_A,
                TENANT_A,
                ORDER_ID,
                VARIANT_A,
                5,
                5,
                0,
                CREATED_AT);
    }

    @Test
    void savesAndRehydratesPartiallyBackorderedCommitment() {

        var commitment =
                commitment(
                        COMMITMENT_A,
                        TENANT_A,
                        ORDER_ID,
                        VARIANT_A,
                        8,
                        5,
                        3,
                        CREATED_AT);

        repository.save(
                commitment);

        var persisted =
                repository.findByOrderAndVariant(
                                TENANT_A,
                                ORDER_ID,
                                VARIANT_A)
                        .orElseThrow();

        assertThat(
                persisted.requestedQuantity())
                .isEqualTo(8);

        assertThat(
                persisted.allocatedQuantity())
                .isEqualTo(5);

        assertThat(
                persisted.backorderedQuantity())
                .isEqualTo(3);
    }

    @Test
    void returnsEmptyWhenCommitmentDoesNotExist() {

        assertThat(
                repository.findByOrderAndVariant(
                        TENANT_A,
                        ORDER_ID,
                        VARIANT_A))
                .isEmpty();
    }

    @Test
    void isolatesSameOrderVariantIdentityAcrossTenants() {

        repository.save(
                commitment(
                        COMMITMENT_A,
                        TENANT_A,
                        ORDER_ID,
                        VARIANT_A,
                        2,
                        2,
                        0,
                        CREATED_AT));

        repository.save(
                commitment(
                        COMMITMENT_A,
                        TENANT_B,
                        ORDER_ID,
                        VARIANT_A,
                        3,
                        1,
                        2,
                        CREATED_AT));

        var tenantA =
                repository.findByOrderAndVariant(
                                TENANT_A,
                                ORDER_ID,
                                VARIANT_A)
                        .orElseThrow();

        var tenantB =
                repository.findByOrderAndVariant(
                                TENANT_B,
                                ORDER_ID,
                                VARIANT_A)
                        .orElseThrow();

        assertThat(
                tenantA.requestedQuantity())
                .isEqualTo(2);

        assertThat(
                tenantA.backorderedQuantity())
                .isZero();

        assertThat(
                tenantB.requestedQuantity())
                .isEqualTo(3);

        assertThat(
                tenantB.backorderedQuantity())
                .isEqualTo(2);
    }

    @Test
    void translatesDuplicateOrderVariantBusinessIdentity() {

        repository.save(
                commitment(
                        COMMITMENT_A,
                        TENANT_A,
                        ORDER_ID,
                        VARIANT_A,
                        1,
                        1,
                        0,
                        CREATED_AT));

        var duplicate =
                commitment(
                        COMMITMENT_B,
                        TENANT_A,
                        ORDER_ID,
                        VARIANT_A,
                        1,
                        1,
                        0,
                        CREATED_AT);

        assertThatThrownBy(() ->
                repository.save(
                        duplicate))
                .isInstanceOf(
                        InventoryPersistenceException.class)
                .hasMessage(
                        "Inventory persistence operation failed.")
                .hasCauseInstanceOf(
                        DataAccessException.class);
    }

    @Test
    void translatesDuplicateCommitmentIdentity() {

        repository.save(
                commitment(
                        COMMITMENT_A,
                        TENANT_A,
                        ORDER_ID,
                        VARIANT_A,
                        1,
                        1,
                        0,
                        CREATED_AT));

        var duplicateIdentity =
                commitment(
                        COMMITMENT_A,
                        TENANT_A,
                        OTHER_ORDER_ID,
                        VARIANT_B,
                        1,
                        1,
                        0,
                        CREATED_AT);

        assertThatThrownBy(() ->
                repository.save(
                        duplicateIdentity))
                .isInstanceOf(
                        InventoryPersistenceException.class)
                .hasMessage(
                        "Inventory persistence operation failed.")
                .hasCauseInstanceOf(
                        DataAccessException.class);
    }

    @Test
    void preservesMicrosecondCreationTimestamp() {

        repository.save(
                commitment(
                        COMMITMENT_A,
                        TENANT_A,
                        ORDER_ID,
                        VARIANT_A,
                        1,
                        1,
                        0,
                        CREATED_AT));

        var persisted =
                repository.findByOrderAndVariant(
                                TENANT_A,
                                ORDER_ID,
                                VARIANT_A)
                        .orElseThrow();

        assertThat(
                persisted.createdAt())
                .isEqualTo(
                        CREATED_AT);
    }

    private static InventoryCommitment commitment(
            UUID commitmentId,
            UUID tenantId,
            UUID orderId,
            UUID variantId,
            long requestedQuantity,
            long allocatedQuantity,
            long backorderedQuantity,
            Instant createdAt) {

        return InventoryCommitment.create(
                commitmentId,
                tenantId,
                orderId,
                variantId,
                requestedQuantity,
                allocatedQuantity,
                backorderedQuantity,
                createdAt);
    }

    private static void assertCommitment(
            InventoryCommitment actual,
            UUID commitmentId,
            UUID tenantId,
            UUID orderId,
            UUID variantId,
            long requestedQuantity,
            long allocatedQuantity,
            long backorderedQuantity,
            Instant createdAt) {

        assertThat(
                actual.commitmentId())
                .isEqualTo(
                        commitmentId);

        assertThat(
                actual.tenantId())
                .isEqualTo(
                        tenantId);

        assertThat(
                actual.orderId())
                .isEqualTo(
                        orderId);

        assertThat(
                actual.variantId())
                .isEqualTo(
                        variantId);

        assertThat(
                actual.requestedQuantity())
                .isEqualTo(
                        requestedQuantity);

        assertThat(
                actual.allocatedQuantity())
                .isEqualTo(
                        allocatedQuantity);

        assertThat(
                actual.backorderedQuantity())
                .isEqualTo(
                        backorderedQuantity);

        assertThat(
                actual.createdAt())
                .isEqualTo(
                        createdAt);
    }
}