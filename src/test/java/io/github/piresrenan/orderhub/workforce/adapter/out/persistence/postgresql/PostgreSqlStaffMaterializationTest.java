package io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffMaterializationConflictException;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffMaterializationPersistenceException;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffProvisioningFactsRepository;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorityBand;

/**
 * Why: provisioning needs an atomic workforce-owned Staff and placement write.
 * Covers: creation, exact replay, inconsistent state and ambient rollback.
 * Prevents: partial Staff, implicit reactivation and silent placement overwrite.
 */
@Testcontainers
class PostgreSqlStaffMaterializationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18.6-trixie@sha256:4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
                    .asCompatibleSubstituteFor("postgres"));

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;

    @BeforeAll
    static void migrate() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setTimeout(10);
    }

    @Test
    void createsStaffAndPlacementAndReplaysExactDesiredState() {
        var fixture = fixture();
        var first = transaction.execute(status -> materialize(fixture));
        var second = transaction.execute(status -> materialize(fixture));
        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.staff_profiles WHERE tenant_id = ?",
                Integer.class, fixture.tenant())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT position_id FROM workforce.staff_placements WHERE tenant_id = ?",
                UUID.class, fixture.tenant())).isEqualTo(fixture.position());
    }

    @Test
    void downstreamFailureRollsBackBothRowsAndRetrySucceeds() {
        var fixture = fixture();
        assertThatThrownBy(() -> transaction.execute(status -> {
            materialize(fixture);
            throw new IllegalStateException("Synthetic downstream failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.staff_profiles WHERE tenant_id = ?",
                Integer.class, fixture.tenant())).isZero();
        var retried = transaction.execute(status -> materialize(fixture));
        assertThat(retried).isNotNull();
    }

    @Test
    void inactiveStaffCannotBeImplicitlyReactivated() {
        var fixture = fixture();
        transaction.execute(status -> materialize(fixture));
        jdbc.update("UPDATE workforce.staff_profiles SET status = 'INACTIVE' WHERE tenant_id = ?", fixture.tenant());
        assertThatThrownBy(() -> transaction.execute(status -> materialize(fixture)))
                .isInstanceOf(StaffMaterializationConflictException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM workforce.staff_profiles WHERE tenant_id = ?",
                String.class, fixture.tenant())).isEqualTo("INACTIVE");
    }

    @Test
    void differentPlacementIsAConflictAndCannotOverwriteHistory() {
        var fixture = fixture();
        transaction.execute(status -> materialize(fixture));
        var other = UUID.randomUUID();
        jdbc.update("INSERT INTO workforce.departments VALUES (?, ?, 'OTHER', 'Synthetic other')", other, fixture.tenant());
        var changed = new Fixture(fixture.tenant(), fixture.user(), other, fixture.position());
        assertThatThrownBy(() -> transaction.execute(status -> materialize(changed)))
                .isInstanceOf(StaffMaterializationConflictException.class);
        assertThat(jdbc.queryForObject("SELECT department_id FROM workforce.staff_placements WHERE tenant_id = ?",
                UUID.class, fixture.tenant())).isEqualTo(fixture.department());
    }

    @Test
    void missingPlacementDoesNotMasqueradeAsIdempotentSuccess() {
        var fixture = fixture();
        jdbc.update("INSERT INTO workforce.staff_profiles VALUES (?, ?, ?, 'ACTIVE')",
                UUID.randomUUID(), fixture.user(), fixture.tenant());
        assertThatThrownBy(() -> transaction.execute(status -> materialize(fixture)))
                .isInstanceOf(StaffMaterializationConflictException.class);
    }

    @Test
    void missingOrForeignPositionRollsBackSpeculativeStaff() {
        var fixture = fixture();
        var other = fixture();
        var invalid = new Fixture(fixture.tenant(), fixture.user(), fixture.department(), other.position());
        assertThatThrownBy(() -> transaction.execute(status -> materialize(invalid)))
                .isInstanceOf(StaffMaterializationPersistenceException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.staff_profiles WHERE tenant_id = ?",
                Integer.class, fixture.tenant())).isZero();
    }

    @Test
    void refusesAutocommitBeforeWritingAnyStaff() {
        var fixture = fixture();
        assertThatThrownBy(() -> materialize(fixture)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.staff_profiles WHERE tenant_id = ?",
                Integer.class, fixture.tenant())).isZero();
    }

    @Test
    void transactionOnAnotherDataSourceCannotAllowIndependentAutocommit() {
        var fixture = fixture();
        var unrelatedSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
        var unrelatedTransaction = new TransactionTemplate(new DataSourceTransactionManager(unrelatedSource));
        unrelatedTransaction.setTimeout(10);
        assertThatThrownBy(() -> unrelatedTransaction.execute(status -> materialize(fixture)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.staff_profiles WHERE tenant_id = ?",
                Integer.class, fixture.tenant())).isZero();
    }

    @Test
    void concurrentExactCreationsConvergeAcrossIndependentTransactions() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 32; round++) {
                var fixture = fixture();
                var ready = new CountDownLatch(2);
                var start = new CountDownLatch(1);
                java.util.concurrent.Callable<UUID> work = () -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return transaction.execute(status -> materialize(fixture));
                };
                var first = executor.submit(work);
                var second = executor.submit(work);
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(second.get(15, TimeUnit.SECONDS));
                assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.staff_profiles WHERE tenant_id = ?",
                        Integer.class, fixture.tenant())).isEqualTo(1);
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void provisioningFactsRequireActiveExactPlacementAndSameTenantTarget() {
        var fixture = fixture();
        transaction.execute(status -> materialize(fixture));
        var actor = transaction.execute(status -> facts().actor(fixture.user(), fixture.tenant()));
        assertThat(actor).isPresent();
        assertThat(actor.orElseThrow().band()).isEqualTo(AuthorityBand.OPERATIONAL);
        assertThat(actor.orElseThrow().envelope().permissions()).isEmpty();
        var other = fixture();
        var foreign = transaction.execute(status -> facts().target(fixture.tenant(), other.department(), fixture.position()));
        assertThat(foreign).isEmpty();
        jdbc.update("UPDATE workforce.staff_profiles SET status = 'INACTIVE' WHERE tenant_id = ?", fixture.tenant());
        var inactive = transaction.execute(status -> facts().actor(fixture.user(), fixture.tenant()));
        assertThat(inactive).isEmpty();
    }

    private StaffProvisioningFactsRepository facts() {
        return new PostgreSqlStaffProvisioningFactsRepository(jdbc);
    }

    private UUID materialize(Fixture fixture) {
        return new PostgreSqlStaffMaterializationRepository(jdbc).materialize(
                fixture.tenant(), fixture.user(), fixture.department(), fixture.position());
    }

    private Fixture fixture() {
        var fixture = new Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        jdbc.update("INSERT INTO workforce.departments VALUES (?, ?, 'STAFF', 'Synthetic department')",
                fixture.department(), fixture.tenant());
        jdbc.update("INSERT INTO workforce.job_positions VALUES (?, ?, 'STAFF', 'Synthetic position', 'OPERATIONAL')",
                fixture.position(), fixture.tenant());
        return fixture;
    }

    private record Fixture(UUID tenant, UUID user, UUID department, UUID position) {}
}
