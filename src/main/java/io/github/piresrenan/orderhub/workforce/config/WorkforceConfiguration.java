package io.github.piresrenan.orderhub.workforce.config;

import java.security.SecureRandom;
import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.piresrenan.orderhub.workforce.adapter.out.notification.spring.SpringWorkforceAuthorityChangeAuditNotificationPublisher;
import io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql.PostgreSqlStaffProvisioningIntentRepository;
import io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql.PostgreSqlStaffProvisioningClock;
import io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql.PostgreSqlWorkforceAuditRepository;
import io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql.PostgreSqlWorkforceAuthorityChangeAnalyticsSourceRepository;
import io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql.PostgreSqlWorkforcePositionChangeRepository;
import io.github.piresrenan.orderhub.workforce.adapter.out.transaction.spring.SpringWorkforceTransactionExecutor;
import io.github.piresrenan.orderhub.workforce.application.port.in.IssueStaffProvisioningIntentUseCase;
import io.github.piresrenan.orderhub.workforce.application.port.in.ManageStaffProvisioningUseCase;
import io.github.piresrenan.orderhub.workforce.application.service.StaffProvisioningAdministrationService;
import io.github.piresrenan.orderhub.workforce.application.port.in.ResolveWorkforceAuthorityChangeAnalyticsSourceUseCase;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffProvisioningIntentRepository;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceAuditRepository;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceAuthorityChangeAnalyticsSourceRepository;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceAuthorityChangeAuditNotificationPublisher;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforcePositionChangeRepository;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceTransactionExecutor;
import io.github.piresrenan.orderhub.workforce.application.service.AuditedWorkforceMutationService;
import io.github.piresrenan.orderhub.workforce.application.service.PrivilegedPositionChangeExecutionService;
import io.github.piresrenan.orderhub.workforce.application.service.PrivilegedWorkforceMutationAuthorizationService;
import io.github.piresrenan.orderhub.workforce.application.service.ResolveWorkforceAuthorityChangeAnalyticsSourceService;
import io.github.piresrenan.orderhub.workforce.application.service.StaffProvisioningIssuanceService;
import io.github.piresrenan.orderhub.workforce.application.service.WorkforceAuditRecorder;
import io.github.piresrenan.orderhub.authorization.application.port.in.current.AuthorizeCurrentTenantActionUseCase;
import io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql.PostgreSqlWorkforcePermissionEnvelopeRepository;
import io.github.piresrenan.orderhub.workforce.application.port.in.authorization.AuthorizeStaffTenantActionUseCase;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforcePermissionEnvelopeRepository;
import io.github.piresrenan.orderhub.workforce.application.service.StaffTenantAuthorizationService;
import io.github.piresrenan.orderhub.workforce.application.port.in.ConsumeStaffProvisioningUseCase;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffMaterializationRepository;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffProvisioningCompletion;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffProvisioningEvidenceRepository;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffProvisioningFactsRepository;
import io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql.PostgreSqlStaffMaterializationRepository;
import io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql.PostgreSqlStaffProvisioningEvidenceRepository;
import io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql.PostgreSqlStaffProvisioningFactsRepository;
import io.github.piresrenan.orderhub.workforce.application.service.StaffProvisioningConsumptionService;
import io.github.piresrenan.orderhub.workforce.application.service.AuthorizedStaffProvisioningCompletion;
import io.github.piresrenan.orderhub.authorization.application.port.in.provisioning.StaffProvisioningAuthorizationUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveOrCreateExternalUserUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.EnsureActiveTenantMembershipUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.IsTenantMembershipOperationallyActiveUseCase;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.FindTenantOperationalStateUseCase;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StaffProvisioningProperties.class)
public class WorkforceConfiguration {

    @Bean
    io.github.piresrenan.orderhub.workforce.application.service.ColdStartStaffProvisioningService coldStartStaffProvisioningService(
            io.github.piresrenan.orderhub.authorization.application.port.in.provisioning.ColdStartStaffAuthorizationUseCase authorization,
            StaffProvisioningFactsRepository facts, FindTenantOperationalStateUseCase tenants, IssueStaffProvisioningIntentUseCase primitive,
            StaffProvisioningEvidenceRepository evidence, PlatformTransactionManager manager, JdbcTemplate jdbc,
            StaffProvisioningIntentRepository intents) {
        var transaction = new TransactionTemplate(manager);
        transaction.setTimeout(15);
        return new io.github.piresrenan.orderhub.workforce.application.service.ColdStartStaffProvisioningService(authorization,
                new io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql.PostgreSqlColdStartStaffRepository(jdbc),
                facts, tenants, primitive, evidence, new SpringWorkforceTransactionExecutor(transaction), intents,
                new PostgreSqlStaffProvisioningClock(jdbc));
    }

    /** Joins current authorization, the published primitive and owner-local evidence. */
    @Bean
    ManageStaffProvisioningUseCase staffProvisioningAdministrationService(
            StaffProvisioningFactsRepository facts, StaffProvisioningAuthorizationUseCase authorization,
            IsTenantMembershipOperationallyActiveUseCase memberships, FindTenantOperationalStateUseCase tenants,
            StaffProvisioningEvidenceRepository evidence, IssueStaffProvisioningIntentUseCase primitive,
            StaffProvisioningIntentRepository intents, PlatformTransactionManager manager, JdbcTemplate jdbc,
            AuthorizeStaffTenantActionUseCase readAuthority) {
        var transaction = new TransactionTemplate(manager);
        transaction.setTimeout(15);
        return new StaffProvisioningAdministrationService(
                facts, authorization, memberships, tenants, evidence, primitive, intents,
                new SpringWorkforceTransactionExecutor(transaction), new PostgreSqlStaffProvisioningClock(jdbc), readAuthority);
    }

    /** Supplies only workforce-owned Staff materialization persistence. */
    @Bean
    StaffMaterializationRepository staffMaterializationRepository(JdbcTemplate jdbc) {
        return new PostgreSqlStaffMaterializationRepository(jdbc);
    }

    /** Supplies locked current workforce placement facts to the provisioning policy. */
    @Bean
    StaffProvisioningFactsRepository staffProvisioningFactsRepository(JdbcTemplate jdbc) {
        return new PostgreSqlStaffProvisioningFactsRepository(jdbc);
    }

    /** Supplies append-only attribution joined to the provisioned relationship. */
    @Bean
    StaffProvisioningEvidenceRepository staffProvisioningEvidenceRepository(JdbcTemplate jdbc) {
        return new PostgreSqlStaffProvisioningEvidenceRepository(jdbc);
    }

    /** Composes normal Tenant authority, optional role delegation and required evidence. */
    @Bean
    StaffProvisioningCompletion staffProvisioningCompletion(StaffProvisioningFactsRepository facts,
            StaffProvisioningAuthorizationUseCase authorization, IsTenantMembershipOperationallyActiveUseCase memberships,
            FindTenantOperationalStateUseCase tenants, StaffProvisioningEvidenceRepository evidence,
            io.github.piresrenan.orderhub.workforce.application.service.ColdStartStaffProvisioningService coldStart) {
        return new io.github.piresrenan.orderhub.workforce.application.service.ColdStartAwareStaffProvisioningCompletion(
                new AuthorizedStaffProvisioningCompletion(facts, authorization, memberships, tenants, evidence), coldStart, evidence);
    }

    /**
     * Bounds the complete provisioning transaction to 15 seconds. REQUIRED joins
     * an existing physical transaction; no inner capability commits independently.
     */
    @Bean
    ConsumeStaffProvisioningUseCase consumeStaffProvisioningUseCase(StaffProvisioningIntentRepository intents,
            ResolveOrCreateExternalUserUseCase users, EnsureActiveTenantMembershipUseCase memberships,
            StaffMaterializationRepository staff, StaffProvisioningCompletion completion,
            PlatformTransactionManager transactionManager, JdbcTemplate jdbc) {
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setTimeout(15);
        return new StaffProvisioningConsumptionService(intents, users, memberships, staff, completion,
                new SpringWorkforceTransactionExecutor(transaction), new PostgreSqlStaffProvisioningClock(jdbc));
    }

    /**
     * Exposes the workforce-owned Staff provisioning intent persistence port.
     */
    @Bean
    StaffProvisioningIntentRepository staffProvisioningIntentRepository(
            JdbcTemplate jdbcTemplate) {

        return new PostgreSqlStaffProvisioningIntentRepository(
                jdbcTemplate);
    }

    /**
     * Composes one singleton issuance use case with explicit UTC time,
     * cryptographic entropy and externally validated credential lifetime.
     *
     * <p>Clock and entropy remain composition-owned implementation details
     * rather than global container services.</p>
     */
    @Bean
    IssueStaffProvisioningIntentUseCase issueStaffProvisioningIntentUseCase(
            StaffProvisioningIntentRepository repository,
            StaffProvisioningProperties properties) {

        return new StaffProvisioningIssuanceService(
                repository,
                Clock.systemUTC(),
                properties.intentTtl(),
                new SecureRandom());
    }
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
