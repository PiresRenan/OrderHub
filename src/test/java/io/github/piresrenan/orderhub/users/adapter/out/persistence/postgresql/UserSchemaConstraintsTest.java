package io.github.piresrenan.orderhub.users.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class UserSchemaConstraintsTest {

        private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse(
                        "postgres:18.6-trixie@sha256:"
                                        + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
                        .asCompatibleSubstituteFor("postgres");

        @Container
        private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
                        .withDatabaseName("orderhub_test")
                        .withUsername("orderhub_test")
                        .withPassword("synthetic-test-password");

        private static JdbcTemplate jdbcTemplate;

        @BeforeAll
        static void migrateDatabase() {
                // Why: schema constraints must be verified against the exact cumulative
                // Flyway history used by production.
                // Covers: creation of a clean PostgreSQL database through the current
                // accepted migration history.
                // Prevents: constraint tests silently depending on manually prepared schema.

                var dataSource = new DriverManagerDataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword());

                Flyway.configure()
                                .dataSource(dataSource)
                                .locations("classpath:db/migration")
                                .load()
                                .migrate();

                jdbcTemplate = new JdbcTemplate(dataSource);
        }

        @BeforeEach
        void clearUsersSchema() {
                // Why: each persistence-invariant test needs deterministic isolated state.
                // Covers: cleanup of data owned by the Users module.
                // Prevents: one test's rows influencing another test's constraint result.

                jdbcTemplate.update("""
                                TRUNCATE TABLE
                                    users.external_identity_bindings,
                                    users.tenant_memberships,
                                    users.users
                                """);
        }

        @Test
        void rejectsUserWithoutId() {
                // Why: database constraints must defend User identity even when application
                // validation is bypassed.
                // Covers: NOT NULL / primary-key protection for users.users.id.
                // Prevents: identity-less User rows entering durable state.

                assertThatThrownBy(() -> jdbcTemplate.update(
                                "INSERT INTO users.users (id) VALUES (?)",
                                new Object[] { null }))
                                .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void rejectsDuplicateUserId() {
                // Why: one internal User identity must represent at most one durable User.
                // Covers: primary-key uniqueness for users.users.id.
                // Prevents: duplicate rows representing the same internal identity.

                var userId = UUID.randomUUID();

                jdbcTemplate.update(
                                "INSERT INTO users.users (id) VALUES (?)",
                                userId);

                assertThatThrownBy(() -> jdbcTemplate.update(
                                "INSERT INTO users.users (id) VALUES (?)",
                                userId))
                                .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void rejectsMembershipWithoutUserId() {
                // Why: membership cannot represent an association without User identity.
                // Covers: NOT NULL protection for tenant_memberships.user_id.
                // Prevents: structurally incomplete membership rows.

                assertThatThrownBy(() -> jdbcTemplate.update(
                                """
                                                INSERT INTO users.tenant_memberships (
                                                    user_id,
                                                    tenant_id
                                                )
                                                VALUES (?, ?)
                                                """,
                                null,
                                UUID.randomUUID()))
                                .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void rejectsMembershipWithoutTenantId() {
                // Why: membership cannot represent an association without Tenant identity.
                // Covers: NOT NULL protection for tenant_memberships.tenant_id.
                // Prevents: structurally incomplete membership rows.

                assertThatThrownBy(() -> jdbcTemplate.update(
                                """
                                                INSERT INTO users.tenant_memberships (
                                                    user_id,
                                                    tenant_id
                                                )
                                                VALUES (?, ?)
                                                """,
                                UUID.randomUUID(),
                                null))
                                .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void rejectsDuplicateUserTenantMembership() {
                // Why: membership is a set-like association; the same User/Tenant pair must
                // not be persisted more than once.
                // Covers: durable uniqueness of (tenant_id, user_id).
                // Prevents: duplicate memberships producing ambiguous counts and future
                // authorization inconsistencies.

                var userId = UUID.randomUUID();
                var tenantId = UUID.randomUUID();

                persistUser(userId);

                jdbcTemplate.update(
                                """
                                                INSERT INTO users.tenant_memberships (
                                                    user_id,
                                                    tenant_id
                                                )
                                                VALUES (?, ?)
                                                """,
                                userId,
                                tenantId);

                assertThatThrownBy(() -> jdbcTemplate.update(
                                """
                                                INSERT INTO users.tenant_memberships (
                                                    user_id,
                                                    tenant_id
                                                )
                                                VALUES (?, ?)
                                                """,
                                userId,
                                tenantId))
                                .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void allowsSameUserInDifferentTenants() {
                // Why: one User may legitimately belong to multiple Tenants.
                // Covers: uniqueness being scoped to the complete User/Tenant pair.
                // Prevents: accidentally constraining user_id globally in memberships.

                var userId = UUID.randomUUID();
                var firstTenantId = UUID.randomUUID();
                var secondTenantId = UUID.randomUUID();

                persistUser(userId);

                jdbcTemplate.update(
                                """
                                                INSERT INTO users.tenant_memberships (
                                                    user_id,
                                                    tenant_id
                                                )
                                                VALUES (?, ?)
                                                """,
                                userId,
                                firstTenantId);

                jdbcTemplate.update(
                                """
                                                INSERT INTO users.tenant_memberships (
                                                    user_id,
                                                    tenant_id
                                                )
                                                VALUES (?, ?)
                                                """,
                                userId,
                                secondTenantId);

                var count = jdbcTemplate.queryForObject(
                                """
                                                SELECT COUNT(*)
                                                FROM users.tenant_memberships
                                                WHERE user_id = ?
                                                """,
                                Integer.class,
                                userId);

                assertThat(count)
                                .isEqualTo(2);
        }

        @Test
        void allowsDifferentUsersInSameTenant() {
                // Why: one Tenant must support membership for multiple Users.
                // Covers: uniqueness being scoped to the complete User/Tenant pair.
                // Prevents: accidentally constraining tenant_id globally.

                var firstUserId = UUID.randomUUID();
                var secondUserId = UUID.randomUUID();
                var tenantId = UUID.randomUUID();

                persistUser(firstUserId);
                persistUser(secondUserId);

                jdbcTemplate.update(
                                """
                                                INSERT INTO users.tenant_memberships (
                                                    user_id,
                                                    tenant_id
                                                )
                                                VALUES (?, ?)
                                                """,
                                firstUserId,
                                tenantId);

                jdbcTemplate.update(
                                """
                                                INSERT INTO users.tenant_memberships (
                                                    user_id,
                                                    tenant_id
                                                )
                                                VALUES (?, ?)
                                                """,
                                secondUserId,
                                tenantId);

                var count = jdbcTemplate.queryForObject(
                                """
                                                SELECT COUNT(*)
                                                FROM users.tenant_memberships
                                                WHERE tenant_id = ?
                                                """,
                                Integer.class,
                                tenantId);

                assertThat(count)
                                .isEqualTo(2);
        }

        @Test
        void hasNoCrossModuleForeignKeyToTenantsSchema() {
                // Why: Users may reference Tenant identity but must not acquire database-level
                // ownership coupling to the Tenants module.
                // Covers: absence of foreign keys from users.tenant_memberships to the
                // tenants schema.
                // Prevents: an accidental cross-module relational dependency bypassing
                // application/module boundaries.

                var crossModuleForeignKeys = jdbcTemplate.queryForObject(
                                """
                                                SELECT COUNT(*)
                                                FROM information_schema.table_constraints tc
                                                JOIN information_schema.constraint_column_usage ccu
                                                  ON tc.constraint_name = ccu.constraint_name
                                                 AND tc.constraint_schema = ccu.constraint_schema
                                                WHERE tc.constraint_type = 'FOREIGN KEY'
                                                  AND tc.table_schema = 'users'
                                                  AND tc.table_name = 'tenant_memberships'
                                                  AND ccu.table_schema = 'tenants'
                                                """,
                                Integer.class);

                assertThat(crossModuleForeignKeys)
                                .isZero();
        }

        @Test
        void rejectsMembershipForNonExistingUser() {
                // Why: a durable membership must always reference an internal User owned by
                // the Users module.
                // Covers: database-level referential integrity between tenant_memberships
                // and users.
                // Prevents: orphan memberships being persisted for arbitrary or stale User
                // identifiers.

                var unknownUserId = UUID.randomUUID();
                var tenantId = UUID.randomUUID();

                assertThatThrownBy(() -> jdbcTemplate.update(
                                """
                                                INSERT INTO users.tenant_memberships (
                                                    user_id,
                                                    tenant_id
                                                )
                                                VALUES (?, ?)
                                                """,
                                unknownUserId,
                                tenantId))
                                .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void rejectsExternalIdentityBindingWithoutIssuer() {
                // Why: an external identity cannot exist without its authority namespace.
                // Covers: database-level NOT NULL protection for issuer.
                // Prevents: persistence bypasses creating structurally incomplete identities.

                var userId = UUID.randomUUID();

                persistUser(userId);

                assertThatThrownBy(() -> jdbcTemplate.update("""
                                INSERT INTO users.external_identity_bindings (
                                    issuer,
                                    subject,
                                    user_id
                                )
                                VALUES (?, ?, ?)
                                """,
                                null,
                                "synthetic-subject-001",
                                userId))
                                .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void rejectsExternalIdentityBindingWithoutSubject() {
                // Why: issuer alone cannot identify one external principal.
                // Covers: database-level NOT NULL protection for subject.
                // Prevents: incomplete provider identities entering durable state.

                var userId = UUID.randomUUID();

                persistUser(userId);

                assertThatThrownBy(() -> jdbcTemplate.update("""
                                INSERT INTO users.external_identity_bindings (
                                    issuer,
                                    subject,
                                    user_id
                                )
                                VALUES (?, ?, ?)
                                """,
                                "https://issuer.example.test",
                                null,
                                userId))
                                .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void rejectsExternalIdentityBindingWithoutUserId() {
                // Why: every external identity must resolve to one internal User.
                // Covers: database-level NOT NULL protection for user_id.
                // Prevents: durable bindings with no internal identity target.

                assertThatThrownBy(() -> jdbcTemplate.update("""
                                INSERT INTO users.external_identity_bindings (
                                    issuer,
                                    subject,
                                    user_id
                                )
                                VALUES (?, ?, ?)
                                """,
                                "https://issuer.example.test",
                                "synthetic-subject-001",
                                null))
                                .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void rejectsDuplicateIssuerSubjectBinding() {
                // Why: one exact external identity may map to at most one internal User.
                // Covers: durable uniqueness of the complete (issuer, subject) pair.
                // Prevents: ambiguous authentication resolution under concurrency or repeated
                // provisioning.

                var firstUserId = UUID.randomUUID();
                var secondUserId = UUID.randomUUID();
                var issuer = "https://issuer.example.test";
                var subject = "synthetic-subject-001";

                persistUser(firstUserId);
                persistUser(secondUserId);

                persistExternalIdentity(
                                issuer,
                                subject,
                                firstUserId);

                assertThatThrownBy(() -> persistExternalIdentity(
                                issuer,
                                subject,
                                secondUserId))
                                .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void rejectsExternalIdentityBindingForNonExistingUser() {
                // Why: a durable external identity binding must always resolve to an internal
                // User owned by this module.
                // Covers: same-module foreign key from external identity binding to
                // users.users.
                // Prevents: authentication identities resolving to arbitrary or stale User IDs.

                assertThatThrownBy(() -> persistExternalIdentity(
                                "https://issuer.example.test",
                                "synthetic-subject-001",
                                UUID.randomUUID()))
                                .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void preventsDeletingUserReferencedByExternalIdentity() {
                // Why: deleting a User must not silently erase or orphan authentication
                // identity
                // state.
                // Covers: restrictive same-module User foreign-key semantics without cascade.
                // Prevents: identity bindings disappearing implicitly during User deletion.

                var userId = UUID.randomUUID();

                persistUser(userId);

                persistExternalIdentity(
                                "https://issuer.example.test",
                                "synthetic-subject-001",
                                userId);

                assertThatThrownBy(() -> jdbcTemplate.update("""
                                DELETE FROM users.users
                                WHERE id = ?
                                """,
                                userId))
                                .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void allowsSameUserToHaveMultipleExternalIdentities() {
                // Why: one internal User may legitimately be reachable through more than one
                // external identity.
                // Covers: user_id deliberately not being globally unique.
                // Prevents: persistence accidentally enforcing one provider identity per User.

                var userId = UUID.randomUUID();

                persistUser(userId);

                persistExternalIdentity(
                                "https://issuer-one.example.test",
                                "synthetic-subject-001",
                                userId);

                persistExternalIdentity(
                                "https://issuer-two.example.test",
                                "synthetic-subject-002",
                                userId);

                var count = jdbcTemplate.queryForObject("""
                                SELECT COUNT(*)
                                FROM users.external_identity_bindings
                                WHERE user_id = ?
                                """,
                                Integer.class,
                                userId);

                assertThat(count)
                                .isEqualTo(2);
        }

        @Test
        void treatsCaseDistinctExternalIdentitiesAsDifferentPairs() {
                // Why: issuer and subject have exact case-sensitive identity semantics.
                // Covers: durable uniqueness preserving distinct case variants.
                // Prevents: persistence introducing lowercase or case-insensitive identity
                // equivalence not present in the application contract.

                var userId = UUID.randomUUID();

                persistUser(userId);

                persistExternalIdentity(
                                "https://Issuer.Example.test",
                                "Synthetic-Subject",
                                userId);

                persistExternalIdentity(
                                "https://issuer.example.test",
                                "synthetic-subject",
                                userId);

                var count = jdbcTemplate.queryForObject("""
                                SELECT COUNT(*)
                                FROM users.external_identity_bindings
                                WHERE user_id = ?
                                """,
                                Integer.class,
                                userId);

                assertThat(count)
                                .isEqualTo(2);
        }

        @Test
        void hasNoExternalIdentityForeignKeyOutsideUsersSchema() {
                // Why: external identity persistence belongs completely to Users and must not
                // acquire relational ownership coupling to another module.
                // Covers: every foreign-key target of external_identity_bindings remaining
                // inside the users schema.
                // Prevents: accidental cross-module database dependencies bypassing application
                // boundaries.

                var crossModuleForeignKeys = jdbcTemplate.queryForObject("""
                                SELECT COUNT(*)
                                FROM information_schema.table_constraints tc
                                JOIN information_schema.constraint_column_usage ccu
                                  ON tc.constraint_name = ccu.constraint_name
                                 AND tc.constraint_schema = ccu.constraint_schema
                                WHERE tc.constraint_type = 'FOREIGN KEY'
                                  AND tc.table_schema = 'users'
                                  AND tc.table_name = 'external_identity_bindings'
                                  AND ccu.table_schema <> 'users'
                                """,
                                Integer.class);

                assertThat(crossModuleForeignKeys)
                                .isZero();
        }

        /**
         * Persists one synthetic external identity binding directly through SQL.
         *
         * <p>
         * Direct SQL deliberately bypasses application/domain validation so schema
         * constraints are proven as an independent durable defense.
         * </p>
         *
         * @param issuer  exact synthetic external identity issuer
         * @param subject exact synthetic external identity subject
         * @param userId  synthetic internal User target
         */
        private void persistExternalIdentity(
                        String issuer,
                        String subject,
                        UUID userId) {

                jdbcTemplate.update("""
                                INSERT INTO users.external_identity_bindings (
                                    issuer,
                                    subject,
                                    user_id
                                )
                                VALUES (?, ?, ?)
                                """,
                                issuer,
                                subject,
                                userId);
        }

        @Test
        void rejectsExternalIdentityIssuerExceedingStorageByteBudget() {
                // Why: issuer participates in the durable unique B-tree key and therefore
                // cannot grow without a storage-level bound.
                // Covers: maximum 1024-byte issuer budget enforced independently by PostgreSQL.
                // Prevents: externally controlled issuer values exceeding the supported
                // persistence/indexing contract.

                var userId = UUID.randomUUID();

                persistUser(userId);

                var oversizedIssuer = "i".repeat(1025);

                assertThatThrownBy(() -> persistExternalIdentity(
                                oversizedIssuer,
                                "synthetic-subject-001",
                                userId))
                                .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void rejectsExternalIdentitySubjectExceedingStorageByteBudget() {
                // Why: subject participates in the durable unique B-tree key and therefore
                // requires the same explicit storage bound as issuer.
                // Covers: maximum 1024-byte subject budget enforced by PostgreSQL.
                // Prevents: externally controlled subjects exceeding the supported
                // persistence/indexing contract.

                var userId = UUID.randomUUID();

                persistUser(userId);

                var oversizedSubject = "s".repeat(1025);

                assertThatThrownBy(() -> persistExternalIdentity(
                                "https://issuer.example.test",
                                oversizedSubject,
                                userId))
                                .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void measuresExternalIdentityBudgetInBytesRatherThanCharacters() {
                // Why: external identity values are persisted and indexed as encoded bytes,
                // while one Unicode character may occupy multiple bytes.
                // Covers: byte-oriented rather than character-oriented storage protection.
                // Prevents: multibyte identities bypassing the intended B-tree size budget.

                var userId = UUID.randomUUID();

                persistUser(userId);

                // UTF-8 encodes this synthetic value above the 1024-byte budget while its
                // Java character count remains below 1024.
                var oversizedMultibyteSubject = "é".repeat(513);

                assertThatThrownBy(() -> persistExternalIdentity(
                                "https://issuer.example.test",
                                oversizedMultibyteSubject,
                                userId))
                                .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void allowsExternalIdentityAtMaximumSupportedByteBudget() {
                // Why: the defensive bound must not reject identities exactly at the
                // documented supported maximum.
                // Covers: simultaneous 1024-byte issuer and subject values in the unique key.
                // Prevents: an overly restrictive constraint or B-tree design that cannot
                // actually store the advertised boundary.

                var userId = UUID.randomUUID();

                persistUser(userId);

                var maximumIssuer = "i".repeat(1024);
                var maximumSubject = "s".repeat(1024);

                persistExternalIdentity(
                                maximumIssuer,
                                maximumSubject,
                                userId);

                var count = jdbcTemplate.queryForObject("""
                                SELECT COUNT(*)
                                FROM users.external_identity_bindings
                                WHERE issuer = ?
                                  AND subject = ?
                                """,
                                Integer.class,
                                maximumIssuer,
                                maximumSubject);

                assertThat(count)
                                .isEqualTo(1);
        }

        /**
         * Persists one synthetic internal User required by membership constraints.
         *
         * @param userId synthetic internal User identifier used by the current test
         */
        private void persistUser(
                        UUID userId) {

                jdbcTemplate.update(
                                """
                                                INSERT INTO users.users (
                                                    id
                                                )
                                                VALUES (?)
                                                """,
                                userId);
        }
}
