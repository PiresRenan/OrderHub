package io.github.piresrenan.orderhub.workforce.config;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.piresrenan.orderhub.workforce.adapter.out.notification.spring.SpringWorkforceAuthorityChangeAuditNotificationPublisher;
import io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql.PostgreSqlWorkforceAuditRepository;
import io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql.PostgreSqlWorkforceAuthorityChangeAnalyticsSourceRepository;
import io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql.PostgreSqlWorkforcePositionChangeRepository;
import io.github.piresrenan.orderhub.workforce.adapter.out.transaction.spring.SpringWorkforceTransactionExecutor;
import io.github.piresrenan.orderhub.workforce.application.port.in.ResolveWorkforceAuthorityChangeAnalyticsSourceUseCase;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceAuditRepository;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceAuthorityChangeAnalyticsSourceRepository;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceAuthorityChangeAuditNotificationPublisher;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforcePositionChangeRepository;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceTransactionExecutor;
import io.github.piresrenan.orderhub.workforce.application.service.AuditedWorkforceMutationService;
import io.github.piresrenan.orderhub.workforce.application.service.PrivilegedPositionChangeExecutionService;
import io.github.piresrenan.orderhub.workforce.application.service.PrivilegedWorkforceMutationAuthorizationService;
import io.github.piresrenan.orderhub.workforce.application.service.ResolveWorkforceAuthorityChangeAnalyticsSourceService;
import io.github.piresrenan.orderhub.workforce.application.service.WorkforceAuditRecorder;
import io.github.piresrenan.orderhub.authorization.application.port.in.current.AuthorizeCurrentTenantActionUseCase;
import io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql.PostgreSqlWorkforcePermissionEnvelopeRepository;
import io.github.piresrenan.orderhub.workforce.application.port.in.authorization.AuthorizeStaffTenantActionUseCase;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforcePermissionEnvelopeRepository;
import io.github.piresrenan.orderhub.workforce.application.service.StaffTenantAuthorizationService;

@Configuration(proxyBeanMethods = false)
public class WorkforceConfiguration {

    /** Exposes only workforce-owned lookup to the current-authority application service. */
    @Bean
    WorkforcePermissionEnvelopeRepository workforcePermissionEnvelopeRepository(JdbcTemplate jdbcTemplate) {
        return new PostgreSqlWorkforcePermissionEnvelopeRepository(jdbcTemplate);
    }

    /** Composes the narrow Staff facade without importing authorization persistence. */
    @Bean
    AuthorizeStaffTenantActionUseCase authorizeStaffTenantActionUseCase(
            AuthorizeCurrentTenantActionUseCase authorization, WorkforcePermissionEnvelopeRepository envelopes) {
        return new StaffTenantAuthorizationService(authorization, envelopes);
    }

    @Bean
    WorkforceAuditRepository workforceAuditRepository(
            JdbcTemplate jdbcTemplate) {

        return new PostgreSqlWorkforceAuditRepository(
                jdbcTemplate);
    }

    @Bean
    WorkforceAuthorityChangeAuditNotificationPublisher
            workforceAuthorityChangeAuditNotificationPublisher(
                    ApplicationEventPublisher applicationEventPublisher) {

        return new SpringWorkforceAuthorityChangeAuditNotificationPublisher(
                applicationEventPublisher);
    }

    @Bean
    WorkforceAuditRecorder workforceAuditRecorder(
            WorkforceAuditRepository auditRepository,
            WorkforceAuthorityChangeAuditNotificationPublisher
                    notificationPublisher) {

        return new WorkforceAuditRecorder(
                auditRepository,
                notificationPublisher);
    }

    @Bean
    WorkforceAuthorityChangeAnalyticsSourceRepository
            workforceAuthorityChangeAnalyticsSourceRepository(
                    JdbcTemplate jdbcTemplate) {

        return new PostgreSqlWorkforceAuthorityChangeAnalyticsSourceRepository(
                jdbcTemplate);
    }

    @Bean
    ResolveWorkforceAuthorityChangeAnalyticsSourceUseCase
            resolveWorkforceAuthorityChangeAnalyticsSourceUseCase(
                    WorkforceAuthorityChangeAnalyticsSourceRepository
                            analyticsSourceRepository) {

        return new ResolveWorkforceAuthorityChangeAnalyticsSourceService(
                analyticsSourceRepository);
    }

    @Bean
    WorkforcePositionChangeRepository workforcePositionChangeRepository(
            JdbcTemplate jdbcTemplate) {

        return new PostgreSqlWorkforcePositionChangeRepository(
                jdbcTemplate);
    }

    @Bean
    WorkforceTransactionExecutor workforceTransactionExecutor(
            PlatformTransactionManager transactionManager) {

        var transactionTemplate =
                new TransactionTemplate(
                        transactionManager);

        return new SpringWorkforceTransactionExecutor(
                transactionTemplate);
    }

    @Bean
    PrivilegedWorkforceMutationAuthorizationService
            privilegedWorkforceMutationAuthorizationService() {

        return new PrivilegedWorkforceMutationAuthorizationService();
    }

    @Bean
    AuditedWorkforceMutationService auditedWorkforceMutationService(
            WorkforceTransactionExecutor transactionExecutor,
            WorkforceAuditRecorder auditRecorder) {

        return new AuditedWorkforceMutationService(
                transactionExecutor,
                auditRecorder);
    }

    @Bean
    PrivilegedPositionChangeExecutionService
            privilegedPositionChangeExecutionService(
                    WorkforceTransactionExecutor transactionExecutor,
                    WorkforcePositionChangeRepository positionRepository,
                    WorkforceAuditRecorder auditRecorder,
                    PrivilegedWorkforceMutationAuthorizationService authorizationService) {

        return new PrivilegedPositionChangeExecutionService(
                transactionExecutor,
                positionRepository,
                auditRecorder,
                authorizationService);
    }
}
