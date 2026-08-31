package io.github.piresrenan.orderhub.users;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import io.github.piresrenan.orderhub.users.adapter.out.persistence.postgresql.PostgreSqlTenantMembershipRepository;
import io.github.piresrenan.orderhub.users.adapter.out.persistence.postgresql.PostgreSqlUserRepository;
import io.github.piresrenan.orderhub.users.application.port.in.CreateUserUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.EstablishTenantMembershipUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.FindTenantMembershipUseCase;
import io.github.piresrenan.orderhub.users.application.port.out.TenantMembershipRepository;
import io.github.piresrenan.orderhub.users.application.port.out.UserIdGenerator;
import io.github.piresrenan.orderhub.users.application.port.out.UserRepository;
import io.github.piresrenan.orderhub.users.application.service.CreateUserService;
import io.github.piresrenan.orderhub.users.application.service.EstablishTenantMembershipService;
import io.github.piresrenan.orderhub.users.application.service.FindTenantMembershipService;
import io.github.piresrenan.orderhub.users.adapter.out.persistence.postgresql.PostgreSqlExternalIdentityBindingRepository;
import io.github.piresrenan.orderhub.users.application.port.in.BindExternalIdentityUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityUseCase;
import io.github.piresrenan.orderhub.users.application.port.out.ExternalIdentityBindingRepository;
import io.github.piresrenan.orderhub.users.application.service.BindExternalIdentityService;
import io.github.piresrenan.orderhub.users.application.service.ResolveExternalIdentityService;

class UsersConfigurationTest {

        @Test
        void composesUsersModuleThroughExplicitSpringBeans() {
                // Why: Spring must remain a composition concern instead of leaking into
                // domain, application services or persistence implementations.
                // Covers: User, membership and external-identity repositories/use cases plus
                // the internal User identity generator wired from one explicit composition
                // root.
                // Prevents: annotation-driven framework coupling spreading through the module
                // internals or new Users capabilities existing without runtime composition.

                try (var context = new AnnotationConfigApplicationContext()) {
                        context.register(
                                        TestJdbcConfiguration.class,
                                        UsersConfiguration.class);

                        context.refresh();

                        assertThat(context.getBean(UserRepository.class))
                                        .isInstanceOf(PostgreSqlUserRepository.class);

                        assertThat(context.getBean(TenantMembershipRepository.class))
                                        .isInstanceOf(PostgreSqlTenantMembershipRepository.class);

                        assertThat(context.getBean(CreateUserUseCase.class))
                                        .isInstanceOf(CreateUserService.class);

                        assertThat(context.getBean(EstablishTenantMembershipUseCase.class))
                                        .isInstanceOf(EstablishTenantMembershipService.class);

                        assertThat(context.getBean(FindTenantMembershipUseCase.class))
                                        .isInstanceOf(FindTenantMembershipService.class);

                        assertThat(context.getBean(UserIdGenerator.class))
                                        .isNotNull();

                        assertThat(context.getBean(ExternalIdentityBindingRepository.class))
                                        .isInstanceOf(PostgreSqlExternalIdentityBindingRepository.class);

                        assertThat(context.getBean(BindExternalIdentityUseCase.class))
                                        .isInstanceOf(BindExternalIdentityService.class);

                        assertThat(context.getBean(ResolveExternalIdentityUseCase.class))
                                        .isInstanceOf(ResolveExternalIdentityService.class);
                }
        }

        @Test
        void generatesOpaqueUuidUserIdentities() {
                // Why: new User identities must be generated internally without relying on
                // authentication credentials or external identity providers.
                // Covers: concrete UserIdGenerator composition.
                // Prevents: accidental use of mutable or externally meaningful identity
                // values as the core User identifier.

                try (var context = new AnnotationConfigApplicationContext()) {
                        context.register(
                                        TestJdbcConfiguration.class,
                                        UsersConfiguration.class);

                        context.refresh();

                        var generator = context.getBean(
                                        UserIdGenerator.class);

                        var first = generator.generate();
                        var second = generator.generate();

                        assertThat(first)
                                        .isNotNull();

                        assertThat(second)
                                        .isNotNull()
                                        .isNotEqualTo(first);
                }
        }

        @Configuration(proxyBeanMethods = false)
        static class TestJdbcConfiguration {

                /**
                 * Supplies a non-connecting JDBC dependency required only to prove Spring
                 * composition.
                 *
                 * <p>
                 * The DataSource is structurally valid for JdbcTemplate initialization, but
                 * no connection is requested because this test executes no SQL. Real
                 * PostgreSQL behavior remains covered by the repository integration tests.
                 * </p>
                 *
                 * @return JdbcTemplate suitable for composition-only verification
                 */
                @Bean
                JdbcTemplate jdbcTemplate() {
                        var dataSource = new DriverManagerDataSource();

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
