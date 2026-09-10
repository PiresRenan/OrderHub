package io.github.piresrenan.orderhub.users;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import io.github.piresrenan.orderhub.users.adapter.out.persistence.postgresql.PostgreSqlExternalIdentitySerializationCoordinator;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveOrCreateExternalUserUseCase;
import io.github.piresrenan.orderhub.users.application.port.out.ExternalIdentityUserProvisioningCoordinator;
import io.github.piresrenan.orderhub.users.application.service.ResolveOrCreateExternalUserService;

/**
 * Executable runtime composition contract for external User provisioning.
 *
 * <p>The application service and its PostgreSQL serialization coordinator are
 * deliberately free of Spring stereotypes, so a capability that is never
 * declared in the Users composition root simply does not exist at runtime no
 * matter how well it is unit tested. These tests fail exactly in that case.</p>
 *
 * <p>The context is composition-only. Its JDBC infrastructure is structurally
 * valid but never connects, and no transaction is ever executed; real
 * PostgreSQL behaviour stays covered by the persistence integration tests.</p>
 */
class ExternalIdentityResolveOrCreateRuntimeCompositionTest {

    private static final String COORDINATOR_MISSING =
            "Users external identity serialization coordinator runtime composition"
                    + " is missing";

    private static final String COORDINATOR_UNEXPECTED =
            "Users external identity serialization coordinator runtime composition"
                    + " has unexpected implementation";

    private static final String RESOLVE_OR_CREATE_MISSING =
            "Users external identity resolve-or-create runtime composition"
                    + " is missing";

    private static final String RESOLVE_OR_CREATE_UNEXPECTED =
            "Users external identity resolve-or-create runtime composition"
                    + " has unexpected implementation";

    @Test
    void composesExactlyOneExternalIdentityProvisioningCoordinator() {

        // Why: same-identity serialization only protects production if the
        // PostgreSQL coordinator is actually part of the Users composition root.
        // Covers: exactly one ExternalIdentityUserProvisioningCoordinator bean,
        // implemented by the PostgreSQL adapter.
        // Prevents: a proven coordinator that no runtime ever instantiates, and a
        // second competing coordinator silently taking over the capability.

        try (var context =
                new AnnotationConfigApplicationContext()) {

            context.register(
                    TestInfrastructureConfiguration.class,
                    UsersConfiguration.class);

            context.refresh();

            var beans =
                    context.getBeansOfType(
                            ExternalIdentityUserProvisioningCoordinator.class);

            if (beans.size() != 1) {

                throw new AssertionError(
                        COORDINATOR_MISSING);
            }

            var instance =
                    beans.values()
                            .iterator()
                            .next();

            if (!(instance
                    instanceof PostgreSqlExternalIdentitySerializationCoordinator)) {

                throw new AssertionError(
                        COORDINATOR_UNEXPECTED);
            }
        }
    }

    @Test
    void composesExactlyOneResolveOrCreateExternalUserUseCase() {

        // Why: the exposed resolve-or-create capability is what an authenticated
        // caller will depend on, so it must exist as one runtime bean.
        // Covers: exactly one ResolveOrCreateExternalUserUseCase bean, implemented
        // by the Users application service.
        // Prevents: the capability being reachable only from tests, and a second
        // competing implementation being wired alongside it.

        try (var context =
                new AnnotationConfigApplicationContext()) {

            context.register(
                    TestInfrastructureConfiguration.class,
                    UsersConfiguration.class);

            context.refresh();

            var beans =
                    context.getBeansOfType(
                            ResolveOrCreateExternalUserUseCase.class);

            if (beans.size() != 1) {

                throw new AssertionError(
                        RESOLVE_OR_CREATE_MISSING);
            }

            var instance =
                    beans.values()
                            .iterator()
                            .next();

            if (!(instance
                    instanceof ResolveOrCreateExternalUserService)) {

                throw new AssertionError(
                        RESOLVE_OR_CREATE_UNEXPECTED);
            }
        }
    }

    /**
     * Supplies the non-connecting infrastructure the Users composition root needs
     * in order to be instantiated at all.
     *
     * <p>The DataSource is structurally valid so JdbcTemplate and the transaction
     * manager initialize, but it points at an unroutable port and no connection
     * is ever requested because this test executes no SQL and opens no
     * transaction.</p>
     */
    @Configuration(proxyBeanMethods = false)
    static class TestInfrastructureConfiguration {

        @Bean
        io.github.piresrenan.orderhub.users.application.port.out.TrustedExternalIdentityProviders trustedProviders() {
            return issuer -> { throw new AssertionError("Composition must not evaluate provider trust"); };
        }

        /**
         * Provides the synthetic composition-only DataSource.
         *
         * @return structurally valid DataSource that is never connected
         */
        @Bean
        DataSource dataSource() {

            var dataSource =
                    new DriverManagerDataSource();

            dataSource.setDriverClassName(
                    "org.postgresql.Driver");

            dataSource.setUrl(
                    "jdbc:postgresql://127.0.0.1:1/orderhub_composition_test");

            dataSource.setUsername(
                    "synthetic-composition-user");

            dataSource.setPassword(
                    "synthetic-composition-password");

            return dataSource;
        }

        /**
         * Provides the JDBC dependency required by the Users composition root.
         *
         * @param dataSource synthetic composition-only DataSource
         * @return JdbcTemplate suitable for composition-only verification
         */
        @Bean
        JdbcTemplate jdbcTemplate(
                DataSource dataSource) {

            return new JdbcTemplate(
                    dataSource);
        }

        /**
         * Provides the transaction demarcation the PostgreSQL provisioning
         * coordinator depends on once it is composed.
         *
         * @param dataSource synthetic composition-only DataSource
         * @return transaction manager suitable for composition-only verification
         */
        @Bean
        PlatformTransactionManager transactionManager(
                DataSource dataSource) {

            return new JdbcTransactionManager(
                    dataSource);
        }
    }
}
