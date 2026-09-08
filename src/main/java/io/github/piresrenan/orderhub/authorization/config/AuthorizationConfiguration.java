package io.github.piresrenan.orderhub.authorization.config;

import java.util.UUID;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import io.github.piresrenan.orderhub.authorization.adapter.out.persistence.postgresql.PostgreSqlAdministrativeGrantAuditRepository;
import io.github.piresrenan.orderhub.authorization.adapter.out.persistence.postgresql.PostgreSqlAdministrativeGrantRepository;
import io.github.piresrenan.orderhub.authorization.adapter.out.transaction.spring.SpringAuthorizationTransactionExecutor;
import io.github.piresrenan.orderhub.authorization.application.port.in.AuthorizeCustomerOwnedResourceActionUseCase;
import io.github.piresrenan.orderhub.authorization.application.port.in.administration.AuthorizeAdministrativeActionUseCase;
import io.github.piresrenan.orderhub.authorization.application.port.in.administration.MutateAdministrativeGrantUseCase;
import io.github.piresrenan.orderhub.authorization.application.port.out.AdministrativeGrantAuditRepository;
import io.github.piresrenan.orderhub.authorization.application.port.out.AdministrativeGrantRepository;
import io.github.piresrenan.orderhub.authorization.application.port.out.AuthorizationTransactionExecutor;
import io.github.piresrenan.orderhub.authorization.application.service.AdministrativeAuthorizationService;
import io.github.piresrenan.orderhub.authorization.application.service.AuditedAdministrativeGrantMutationService;
import io.github.piresrenan.orderhub.authorization.application.service.CustomerOwnedResourceAuthorizationService;
import io.github.piresrenan.orderhub.authorization.application.port.in.current.AuthorizeCurrentTenantActionUseCase;
import io.github.piresrenan.orderhub.authorization.application.service.DurableTenantAuthorizationService;
import io.github.piresrenan.orderhub.authorization.adapter.out.persistence.postgresql.PostgreSqlRoleAssignmentRepository;
import io.github.piresrenan.orderhub.authorization.adapter.out.persistence.postgresql.PostgreSqlRoleDefinitionRepository;
import io.github.piresrenan.orderhub.authorization.adapter.out.persistence.postgresql.PostgreSqlUserPermissionOverrideRepository;
import io.github.piresrenan.orderhub.authorization.adapter.out.persistence.postgresql.PostgreSqlAuthorizationDecisionReadTransaction;
import io.github.piresrenan.orderhub.authorization.adapter.out.observability.MicrometerAuthorizationDecisionObserver;
import io.github.piresrenan.orderhub.authorization.domain.constraint.AuthorizationConstraint;
import io.micrometer.core.instrument.MeterRegistry;

@Configuration(proxyBeanMethods = false)
public class AuthorizationConfiguration {

    /** Composes the current-ceiling entry using the existing snapshot, repositories and evaluator. */
    @Bean
    AuthorizeCurrentTenantActionUseCase authorizeCurrentTenantActionUseCase(
            JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager,
            MeterRegistry meterRegistry, List<AuthorizationConstraint> constraints) {
        return new DurableTenantAuthorizationService(
                new PostgreSqlRoleAssignmentRepository(jdbcTemplate),
                new PostgreSqlRoleDefinitionRepository(jdbcTemplate),
                new PostgreSqlUserPermissionOverrideRepository(jdbcTemplate), constraints,
                new PostgreSqlAuthorizationDecisionReadTransaction(transactionManager),
                new MicrometerAuthorizationDecisionObserver(meterRegistry));
    }

    @Bean
    AuthorizeCustomerOwnedResourceActionUseCase authorizeCustomerOwnedResourceActionUseCase() {

        return new CustomerOwnedResourceAuthorizationService();
    }

    @Bean
    AdministrativeGrantRepository administrativeGrantRepository(
            JdbcTemplate jdbcTemplate) {

        return new PostgreSqlAdministrativeGrantRepository(
                jdbcTemplate);
    }

    @Bean
    AdministrativeGrantAuditRepository administrativeGrantAuditRepository(
            JdbcTemplate jdbcTemplate) {

        return new PostgreSqlAdministrativeGrantAuditRepository(
                jdbcTemplate);
    }

    @Bean
    AuthorizationTransactionExecutor authorizationTransactionExecutor(
            PlatformTransactionManager transactionManager) {

        return new SpringAuthorizationTransactionExecutor(
                transactionManager);
    }

    @Bean
    AuditedAdministrativeGrantMutationService auditedAdministrativeGrantMutationService(
            AuthorizationTransactionExecutor transactions,
            AdministrativeGrantRepository grants,
            AdministrativeGrantAuditRepository audit) {

        return new AuditedAdministrativeGrantMutationService(
                transactions,
                grants,
                audit,
                UUID::randomUUID);
    }

    @Bean
    MutateAdministrativeGrantUseCase mutateAdministrativeGrantUseCase(
            AuditedAdministrativeGrantMutationService service) {
        return new MutateAdministrativeGrantUseCase() {
            @Override
            public void grant(UUID actorUserId,
                    io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeGrant grant,
                    UUID correlationId) {
                service.grant(actorUserId, grant, correlationId);
            }

            @Override
            public void revoke(UUID actorUserId,
                    io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeGrant grant,
                    UUID correlationId) {
                service.revoke(actorUserId, grant, correlationId);
            }
        };
    }

    @Bean
    AuthorizeAdministrativeActionUseCase authorizeAdministrativeActionUseCase(
            AdministrativeGrantRepository grants) {

        return new AdministrativeAuthorizationService(
                grants);
    }
}
