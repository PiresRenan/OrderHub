package io.github.piresrenan.orderhub.users;

import java.util.UUID;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

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

/**
 * Spring composition root for the Users application module.
 *
 * <p>
 * Framework wiring is centralized here so domain objects, application services
 * and persistence adapters do not require component-scanning annotations.
 * </p>
 */
@Configuration(proxyBeanMethods = false)
public class UsersConfiguration {

    /**
     * Composes the PostgreSQL implementation of the User persistence boundary.
     *
     * @param jdbcTemplate JDBC infrastructure supplied by Spring
     * @return User repository backed by PostgreSQL
     */
    @Bean
    UserRepository userRepository(
            JdbcTemplate jdbcTemplate) {

        return new PostgreSqlUserRepository(
                jdbcTemplate);
    }

    /**
     * Composes the PostgreSQL implementation of the TenantMembership persistence
     * boundary.
     *
     * @param jdbcTemplate JDBC infrastructure supplied by Spring
     * @return TenantMembership repository backed by PostgreSQL
     */
    @Bean
    TenantMembershipRepository tenantMembershipRepository(
            JdbcTemplate jdbcTemplate) {

        return new PostgreSqlTenantMembershipRepository(
                jdbcTemplate);
    }

    /**
     * Provides opaque internally generated UUID identities for new Users.
     *
     * @return User identity generator independent of authentication mechanisms
     */
    @Bean
    UserIdGenerator userIdGenerator() {
        return UUID::randomUUID;
    }

    /**
     * Composes the User creation application use case.
     *
     * @param userRepository User persistence boundary
     * @param userIdGenerator internal User identity generator
     * @return configured User creation use case
     */
    @Bean
    CreateUserUseCase createUserUseCase(
            UserRepository userRepository,
            UserIdGenerator userIdGenerator) {

        return new CreateUserService(
                userRepository,
                userIdGenerator);
    }

    /**
     * Composes the TenantMembership establishment application use case.
     *
     * @param tenantMembershipRepository membership persistence boundary
     * @return configured membership establishment use case
     */
    @Bean
    EstablishTenantMembershipUseCase establishTenantMembershipUseCase(
            TenantMembershipRepository tenantMembershipRepository) {

        return new EstablishTenantMembershipService(
                tenantMembershipRepository);
    }

    /**
     * Composes the exact-pair TenantMembership query use case.
     *
     * @param tenantMembershipRepository membership persistence boundary
     * @return configured membership query use case
     */
    @Bean
    FindTenantMembershipUseCase findTenantMembershipUseCase(
            TenantMembershipRepository tenantMembershipRepository) {

        return new FindTenantMembershipService(
                tenantMembershipRepository);
    }
}
