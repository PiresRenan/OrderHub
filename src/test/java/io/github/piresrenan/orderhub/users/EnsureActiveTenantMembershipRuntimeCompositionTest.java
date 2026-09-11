package io.github.piresrenan.orderhub.users;

import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import io.github.piresrenan.orderhub.users.application.port.in.EnsureActiveTenantMembershipUseCase;
import io.github.piresrenan.orderhub.users.application.service.EnsureActiveTenantMembershipService;

/**
 * Why: Historical Tenant association must not imply current operational access.
 * Covers: The membership state, concurrency or runtime composition boundary exercised by this suite.
 * Prevents: Implicit reactivation, inconsistent concurrent outcomes and missing production composition.
 */
class EnsureActiveTenantMembershipRuntimeCompositionTest {

    @Test
    void composesExactlyOneEnsureActiveMembershipUseCase() {

        try (var context =
                new AnnotationConfigApplicationContext()) {

            context.register(
                    TestJdbcConfiguration.class,
                    UsersConfiguration.class);

            context.refresh();

            var beans =
                    context.getBeansOfType(
                            EnsureActiveTenantMembershipUseCase.class);

            if (beans.size() != 1) {

                throw new AssertionError(
                        "Users ensure-active runtime composition is missing");
            }

            var instance =
                    beans.values()
                            .iterator()
                            .next();

            if (!(instance
                    instanceof EnsureActiveTenantMembershipService)) {

                throw new AssertionError(
                        "Users ensure-active runtime composition has unexpected implementation");
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TestJdbcConfiguration {

        @Bean
        io.github.piresrenan.orderhub.users.application.port.out.TrustedExternalIdentityProviders trustedProviders() {
            return issuer -> { throw new AssertionError("Composition-only trust must not execute"); };
        }

        @Bean
        JdbcTemplate jdbcTemplate() {

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

            return new JdbcTemplate(
                    dataSource);
        }

        /**
         * Supplies the non-connecting transaction demarcation the Users
         * composition root requires.
         *
         * <p>The manager is built over the DataSource already held by the
         * composition-only JdbcTemplate, so no additional infrastructure is
         * introduced. No transaction is ever started because this test only
         * verifies Spring composition.</p>
         *
         * @param jdbcTemplate composition-only JDBC dependency
         * @return transaction manager suitable for composition-only verification
         */
        @Bean
        PlatformTransactionManager transactionManager(
                JdbcTemplate jdbcTemplate) {

            return new JdbcTransactionManager(
                    Objects.requireNonNull(
                            jdbcTemplate.getDataSource()));
        }
    }
}
