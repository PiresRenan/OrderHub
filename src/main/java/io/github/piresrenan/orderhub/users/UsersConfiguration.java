package io.github.piresrenan.orderhub.users;

import java.util.UUID;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import io.github.piresrenan.orderhub.users.application.port.in.ExternalIdentityLifecycleUseCase;
import io.github.piresrenan.orderhub.users.application.port.out.TrustedExternalIdentityProviders;
import io.github.piresrenan.orderhub.users.application.service.ExternalIdentityLifecycleService;
import io.github.piresrenan.orderhub.users.adapter.out.persistence.postgresql.PostgreSqlExternalIdentityLifecycleRepository;
import io.github.piresrenan.orderhub.users.adapter.out.persistence.postgresql.SpringExternalIdentityLifecycleTransaction;

import io.github.piresrenan.orderhub.users.adapter.out.persistence.postgresql.PostgreSqlTenantMembershipRepository;
import io.github.piresrenan.orderhub.users.adapter.out.persistence.postgresql.PostgreSqlUserRepository;
import io.github.piresrenan.orderhub.users.application.port.in.CreateUserUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.EstablishTenantMembershipUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.EnsureActiveTenantMembershipUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.IsTenantMembershipOperationallyActiveUseCase;
import io.github.piresrenan.orderhub.users.application.port.out.TenantMembershipRepository;
import io.github.piresrenan.orderhub.users.application.port.out.UserIdGenerator;
import io.github.piresrenan.orderhub.users.application.port.out.UserRepository;
import io.github.piresrenan.orderhub.users.application.port.in.UserExistenceUseCase;
import io.github.piresrenan.orderhub.users.application.service.UserExistenceService;
import io.github.piresrenan.orderhub.users.application.service.CreateUserService;
import io.github.piresrenan.orderhub.users.application.service.EstablishTenantMembershipService;
import io.github.piresrenan.orderhub.users.application.service.EnsureActiveTenantMembershipService;
import io.github.piresrenan.orderhub.users.application.service.IsTenantMembershipOperationallyActiveService;
import io.github.piresrenan.orderhub.users.adapter.out.persistence.postgresql.PostgreSqlExternalIdentityBindingRepository;
import io.github.piresrenan.orderhub.users.application.port.in.BindExternalIdentityUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityUseCase;
import io.github.piresrenan.orderhub.users.application.port.out.ExternalIdentityBindingRepository;
import io.github.piresrenan.orderhub.users.application.service.BindExternalIdentityService;
import io.github.piresrenan.orderhub.users.application.service.ResolveExternalIdentityService;
import io.github.piresrenan.orderhub.users.adapter.out.persistence.postgresql.PostgreSqlExternalIdentitySerializationCoordinator;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveOrCreateExternalUserUseCase;
import io.github.piresrenan.orderhub.users.application.port.out.ExternalIdentityUserProvisioningCoordinator;
import io.github.piresrenan.orderhub.users.application.service.ResolveOrCreateExternalUserService;

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

        /** Exposes owner-local lifecycle mutation while requiring the coordinator transaction. */
        @Bean
        io.github.piresrenan.orderhub.users.application.port.in.TransitionTenantMembershipUseCase transitionTenantMembershipUseCase(JdbcTemplate jdbc) {
                return new io.github.piresrenan.orderhub.users.application.service.TransitionTenantMembershipService(
                        new io.github.piresrenan.orderhub.users.adapter.out.persistence.postgresql.PostgreSqlTenantMembershipTransitionRepository(jdbc));
        }

        /** Composes proof consumption, exact-pair serialization and same-User lifecycle evidence. */
        @Bean
        ExternalIdentityLifecycleUseCase externalIdentityLifecycleUseCase(
                        JdbcTemplate jdbc, PlatformTransactionManager manager, ExternalIdentityUserProvisioningCoordinator coordinator,
                        TrustedExternalIdentityProviders providers) {
                var transaction = new TransactionTemplate(manager);
                transaction.setTimeout(15);
                return new ExternalIdentityLifecycleService(
                        new PostgreSqlExternalIdentityLifecycleRepository(jdbc),
                        new SpringExternalIdentityLifecycleTransaction(transaction),
                        coordinator, providers);
        }

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
         * Composes the PostgreSQL implementation of the external identity binding
         * persistence boundary.
         *
         * @param jdbcTemplate JDBC infrastructure supplied by Spring
         * @return ExternalIdentityBinding repository backed by PostgreSQL
         */
        @Bean
        ExternalIdentityBindingRepository externalIdentityBindingRepository(
                        JdbcTemplate jdbcTemplate) {

                return new PostgreSqlExternalIdentityBindingRepository(
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
         * @param userRepository  User persistence boundary
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
         * Composes the Users-owned desired-state TenantMembership use case.
         *
         * <p>
         * The application service remains framework-neutral while this composition
         * root supplies the owner-local persistence boundary required to establish
         * or reconcile one operational User/Tenant membership.
         * </p>
         *
         * @param tenantMembershipRepository membership persistence boundary
         * @return configured ensure-active membership use case
         */
        @Bean
        EnsureActiveTenantMembershipUseCase ensureActiveTenantMembershipUseCase(
                        TenantMembershipRepository tenantMembershipRepository) {

                return new EnsureActiveTenantMembershipService(
                                tenantMembershipRepository);
        }

        /**
         * Composes the exact-pair TenantMembership operational eligibility use
         * case.
         *
         * <p>
         * Publishes the Users-owned operational-membership eligibility boundary
         * without exposing TenantMembership domain state. The domain model and
         * its lifecycle vocabulary stay inside Users.
         * </p>
         *
         * @param tenantMembershipRepository membership persistence boundary
         * @return configured membership eligibility use case
         */
        @Bean
        IsTenantMembershipOperationallyActiveUseCase isTenantMembershipOperationallyActiveUseCase(
                        TenantMembershipRepository tenantMembershipRepository) {

                return new IsTenantMembershipOperationallyActiveService(
                                tenantMembershipRepository);
        }

        /**
         * Composes the external identity binding application use case.
         *
         * @param externalIdentityBindingRepository external identity persistence
         *                                          boundary
         * @return configured external identity binding use case
         */
        @Bean
        BindExternalIdentityUseCase bindExternalIdentityUseCase(
                        ExternalIdentityBindingRepository externalIdentityBindingRepository) {

                return new BindExternalIdentityService(
                                externalIdentityBindingRepository);
        }

        /**
         * Composes the external identity resolution application use case.
         *
         * @param externalIdentityBindingRepository external identity persistence
         *                                          boundary
         * @return configured external identity resolution use case
         */
        @Bean
        ResolveExternalIdentityUseCase resolveExternalIdentityUseCase(
                        ExternalIdentityBindingRepository externalIdentityBindingRepository) {

                return new ResolveExternalIdentityService(
                                externalIdentityBindingRepository);
        }

        /**
         * Composes the PostgreSQL serialization scope used while provisioning the
         * internal User for one exact external identity.
         *
         * @param jdbcTemplate       JDBC infrastructure supplied by Spring
         * @param transactionManager transaction demarcation supplied by Spring
         * @return external identity provisioning coordinator backed by PostgreSQL
         */
        @Bean
        ExternalIdentityUserProvisioningCoordinator externalIdentityUserProvisioningCoordinator(
                        JdbcTemplate jdbcTemplate,
                        PlatformTransactionManager transactionManager) {

                return new PostgreSqlExternalIdentitySerializationCoordinator(
                                jdbcTemplate,
                                transactionManager);
        }

        /**
         * Composes the external identity resolve-or-create application use case.
         *
         * @param externalIdentityUserProvisioningCoordinator serialized
         *                                                    provisioning scope
         * @param resolveExternalIdentityUseCase              external identity
         *                                                    resolution boundary
         * @param createUserUseCase                           User creation
         *                                                    boundary
         * @param bindExternalIdentityUseCase                 external identity
         *                                                    binding boundary
         * @return configured resolve-or-create use case
         */
        @Bean
        ResolveOrCreateExternalUserUseCase resolveOrCreateExternalUserUseCase(
                        ExternalIdentityUserProvisioningCoordinator externalIdentityUserProvisioningCoordinator,
                        ResolveExternalIdentityUseCase resolveExternalIdentityUseCase,
                        CreateUserUseCase createUserUseCase,
                        BindExternalIdentityUseCase bindExternalIdentityUseCase) {

                return new ResolveOrCreateExternalUserService(
                                externalIdentityUserProvisioningCoordinator,
                                resolveExternalIdentityUseCase,
                                createUserUseCase,
                                bindExternalIdentityUseCase);
        }

        @Bean
        UserExistenceUseCase userExistenceUseCase(UserRepository userRepository) {
                return new UserExistenceService(userRepository);
        }
}
