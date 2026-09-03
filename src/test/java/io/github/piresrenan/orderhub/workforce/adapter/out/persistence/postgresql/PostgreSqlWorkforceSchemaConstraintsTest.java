package io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
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
class PostgreSqlWorkforceSchemaConstraintsTest {

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
    void v13ReconstructsTheWorkforceFoundationSchema() {

        var tables =
                jdbcTemplate.queryForList(
                        """
                        SELECT table_name
                        FROM information_schema.tables
                        WHERE table_schema = 'workforce'
                        ORDER BY table_name
                        """,
                        String.class);

        assertThat(tables)
                .contains(
                        "staff_profiles",
                        "departments",
                        "job_positions",
                        "job_position_permissions",
                        "staff_placements",
                        "reporting_relationships");
    }

    @Test
    void v13EnforcesTenantScopedWorkforceIntegrityWithoutCrossModuleForeignKeys() {

        var constraintNames =
                Set.copyOf(
                        jdbcTemplate.queryForList(
                                """
                                SELECT constraint_name
                                FROM information_schema.table_constraints
                                WHERE constraint_schema = 'workforce'
                                """,
                                String.class));

        assertThat(constraintNames)
                .contains(
                        "ck_workforce_staff_status",
                        "uq_workforce_staff_user_tenant",
                        "uq_workforce_department_tenant_code",
                        "ck_workforce_position_authority_band",
                        "uq_workforce_position_tenant_code",
                        "ck_workforce_position_permission_code",
                        "fk_workforce_placement_staff_scope",
                        "fk_workforce_placement_department_scope",
                        "fk_workforce_placement_position_scope",
                        "ck_workforce_reporting_not_self",
                        "fk_workforce_reporting_supervisor_scope",
                        "fk_workforce_reporting_subordinate_scope");

        var crossModuleForeignKeys =
                jdbcTemplate.queryForList(
                        """
                        SELECT
                            source_table.relname
                            || ' -> '
                            || target_namespace.nspname
                            || '.'
                            || target_table.relname
                        FROM pg_constraint constraint_definition
                        JOIN pg_class source_table
                          ON source_table.oid = constraint_definition.conrelid
                        JOIN pg_namespace source_namespace
                          ON source_namespace.oid = source_table.relnamespace
                        JOIN pg_class target_table
                          ON target_table.oid = constraint_definition.confrelid
                        JOIN pg_namespace target_namespace
                          ON target_namespace.oid = target_table.relnamespace
                        WHERE constraint_definition.contype = 'f'
                          AND source_namespace.nspname = 'workforce'
                          AND target_namespace.nspname <> 'workforce'
                        ORDER BY
                            source_table.relname,
                            target_namespace.nspname,
                            target_table.relname
                        """,
                        String.class);

        assertThat(crossModuleForeignKeys)
                .as("workforce must not create relational coupling to other modules")
                .isEmpty();

        var tenantA =
                UUID.randomUUID();

        var tenantB =
                UUID.randomUUID();

        var sharedUserId =
                UUID.randomUUID();

        var staffA =
                UUID.randomUUID();

        var staffB =
                UUID.randomUUID();

        insertStaff(
                staffA,
                sharedUserId,
                tenantA,
                "ACTIVE");

        /*
         * The same User may hold an independent Staff relationship
         * in another Tenant.
         */
        insertStaff(
                staffB,
                sharedUserId,
                tenantB,
                "ACTIVE");

        assertThatThrownBy(() ->
                insertStaff(
                        UUID.randomUUID(),
                        sharedUserId,
                        tenantA,
                        "ACTIVE"))
                .isInstanceOf(
                        DataIntegrityViolationException.class);

        assertThatThrownBy(() ->
                insertStaff(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        tenantA,
                        "SUSPENDED"))
                .isInstanceOf(
                        DataIntegrityViolationException.class);

        var departmentA =
                UUID.randomUUID();

        var departmentB =
                UUID.randomUUID();

        insertDepartment(
                departmentA,
                tenantA,
                "OPS",
                "Operations");

        /*
         * Tenant-scoped codes may be reused independently.
         */
        insertDepartment(
                departmentB,
                tenantB,
                "OPS",
                "Operations");

        assertThatThrownBy(() ->
                insertDepartment(
                        UUID.randomUUID(),
                        tenantA,
                        "OPS",
                        "Duplicate Operations"))
                .isInstanceOf(
                        DataIntegrityViolationException.class);

        var positionA =
                UUID.randomUUID();

        var positionB =
                UUID.randomUUID();

        insertPosition(
                positionA,
                tenantA,
                "OPERATOR",
                "Operator",
                "OPERATIONAL");

        insertPosition(
                positionB,
                tenantB,
                "OPERATOR",
                "Operator",
                "MANAGEMENT");

        assertThatThrownBy(() ->
                insertPosition(
                        UUID.randomUUID(),
                        tenantA,
                        "OPERATOR",
                        "Duplicate Operator",
                        "OPERATIONAL"))
                .isInstanceOf(
                        DataIntegrityViolationException.class);

        assertThatThrownBy(() ->
                insertPosition(
                        UUID.randomUUID(),
                        tenantA,
                        "INVALID_AUTHORITY",
                        "Invalid Authority",
                        "EXECUTIVE"))
                .isInstanceOf(
                        DataIntegrityViolationException.class);

        insertPositionPermission(
                tenantA,
                positionA,
                "CATALOG_VIEW");

        assertThatThrownBy(() ->
                insertPositionPermission(
                        tenantA,
                        positionA,
                        "catalog.view"))
                .isInstanceOf(
                        DataIntegrityViolationException.class);

        assertThatThrownBy(() ->
                insertPositionPermission(
                        tenantB,
                        positionA,
                        "CATALOG_MANAGE"))
                .isInstanceOf(
                        DataIntegrityViolationException.class);

        var validPlacementStaff =
                UUID.randomUUID();

        insertStaff(
                validPlacementStaff,
                UUID.randomUUID(),
                tenantA,
                "ACTIVE");

        insertPlacement(
                tenantA,
                validPlacementStaff,
                departmentA,
                positionA);

        var foreignDepartmentPlacementStaff =
                UUID.randomUUID();

        insertStaff(
                foreignDepartmentPlacementStaff,
                UUID.randomUUID(),
                tenantA,
                "ACTIVE");

        assertThatThrownBy(() ->
                insertPlacement(
                        tenantA,
                        foreignDepartmentPlacementStaff,
                        departmentB,
                        positionA))
                .isInstanceOf(
                        DataIntegrityViolationException.class);

        var foreignPositionPlacementStaff =
                UUID.randomUUID();

        insertStaff(
                foreignPositionPlacementStaff,
                UUID.randomUUID(),
                tenantA,
                "ACTIVE");

        assertThatThrownBy(() ->
                insertPlacement(
                        tenantA,
                        foreignPositionPlacementStaff,
                        departmentA,
                        positionB))
                .isInstanceOf(
                        DataIntegrityViolationException.class);

        insertReportingRelationship(
                tenantA,
                staffA,
                validPlacementStaff);

        assertThatThrownBy(() ->
                insertReportingRelationship(
                        tenantA,
                        staffA,
                        staffA))
                .isInstanceOf(
                        DataIntegrityViolationException.class);

        assertThatThrownBy(() ->
                insertReportingRelationship(
                        tenantA,
                        staffA,
                        staffB))
                .isInstanceOf(
                        DataIntegrityViolationException.class);

        assertThatThrownBy(() ->
                insertReportingRelationship(
                        tenantA,
                        staffB,
                        validPlacementStaff))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    private void insertStaff(
            UUID staffId,
            UUID userId,
            UUID tenantId,
            String status) {

        jdbcTemplate.update(
                """
                INSERT INTO workforce.staff_profiles (
                    staff_id,
                    user_id,
                    tenant_id,
                    status
                )
                VALUES (?, ?, ?, ?)
                """,
                staffId,
                userId,
                tenantId,
                status);
    }

    private void insertDepartment(
            UUID departmentId,
            UUID tenantId,
            String code,
            String name) {

        jdbcTemplate.update(
                """
                INSERT INTO workforce.departments (
                    department_id,
                    tenant_id,
                    code,
                    name
                )
                VALUES (?, ?, ?, ?)
                """,
                departmentId,
                tenantId,
                code,
                name);
    }

    private void insertPosition(
            UUID positionId,
            UUID tenantId,
            String code,
            String title,
            String authorityBand) {

        jdbcTemplate.update(
                """
                INSERT INTO workforce.job_positions (
                    position_id,
                    tenant_id,
                    code,
                    title,
                    authority_band
                )
                VALUES (?, ?, ?, ?, ?)
                """,
                positionId,
                tenantId,
                code,
                title,
                authorityBand);
    }

    private void insertPositionPermission(
            UUID tenantId,
            UUID positionId,
            String permissionCode) {

        jdbcTemplate.update(
                """
                INSERT INTO workforce.job_position_permissions (
                    tenant_id,
                    position_id,
                    permission_code
                )
                VALUES (?, ?, ?)
                """,
                tenantId,
                positionId,
                permissionCode);
    }

    private void insertPlacement(
            UUID tenantId,
            UUID staffId,
            UUID departmentId,
            UUID positionId) {

        jdbcTemplate.update(
                """
                INSERT INTO workforce.staff_placements (
                    tenant_id,
                    staff_id,
                    department_id,
                    position_id
                )
                VALUES (?, ?, ?, ?)
                """,
                tenantId,
                staffId,
                departmentId,
                positionId);
    }

    private void insertReportingRelationship(
            UUID tenantId,
            UUID supervisorStaffId,
            UUID subordinateStaffId) {

        jdbcTemplate.update(
                """
                INSERT INTO workforce.reporting_relationships (
                    tenant_id,
                    supervisor_staff_id,
                    subordinate_staff_id
                )
                VALUES (?, ?, ?)
                """,
                tenantId,
                supervisorStaffId,
                subordinateStaffId);
    }
}
