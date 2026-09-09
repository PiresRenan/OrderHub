package io.github.piresrenan.orderhub.users;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import io.github.piresrenan.orderhub.users.application.port.in.EnsureActiveTenantMembershipUseCase;
import io.github.piresrenan.orderhub.users.application.service.EnsureActiveTenantMembershipService;

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
    }
}
