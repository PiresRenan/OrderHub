package io.github.piresrenan.orderhub.organizations.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import io.github.piresrenan.orderhub.organizations.adapter.out.persistence.postgresql.PostgreSqlOrganizationLifecycleRepository;
import io.github.piresrenan.orderhub.organizations.adapter.out.persistence.postgresql.PostgreSqlOrganizationAdministrativeAuditRepository;
import io.github.piresrenan.orderhub.organizations.adapter.out.transaction.spring.SpringOrganizationTransactionExecutor;
import io.github.piresrenan.orderhub.authorization.application.port.in.administration.AuthorizeAdministrativeActionUseCase;
import io.github.piresrenan.orderhub.authorization.application.port.in.administration.MutateAdministrativeGrantUseCase;
import io.github.piresrenan.orderhub.tenants.application.port.in.administration.FindTenantAdministrativeMetadataUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.UserExistenceUseCase;
import io.github.piresrenan.orderhub.organizations.application.port.in.administration.PlatformOrganizationUseCase;
import io.github.piresrenan.orderhub.organizations.application.port.in.administration.OrganizationAdministrationUseCase;
import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationAdministrativeAuditRepository;
import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationTransactionExecutor;
import io.github.piresrenan.orderhub.organizations.application.service.PlatformOrganizationService;
import io.github.piresrenan.orderhub.organizations.application.service.OrganizationAdministrationService;
import io.github.piresrenan.orderhub.organizations.adapter.out.persistence.postgresql.PostgreSqlOrganizationRepository;
import io.github.piresrenan.orderhub.organizations.adapter.out.persistence.postgresql.PostgreSqlOrganizationTenantPlacementRepository;
import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationLifecycleRepository;
import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationRepository;
import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationTenantPlacementRepository;

@Configuration(proxyBeanMethods = false)
public class OrganizationsConfiguration {

    @Bean
    OrganizationRepository organizationRepository(
            JdbcTemplate jdbcTemplate) {

        return new PostgreSqlOrganizationRepository(
                jdbcTemplate);
    }

    @Bean
    OrganizationLifecycleRepository organizationLifecycleRepository(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager) {

        return new PostgreSqlOrganizationLifecycleRepository(
                jdbcTemplate,
                transactionManager);
    }

    @Bean
    OrganizationTenantPlacementRepository organizationTenantPlacementRepository(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager) {

        return new PostgreSqlOrganizationTenantPlacementRepository(
                jdbcTemplate,
                transactionManager);
    }

    @Bean
    OrganizationAdministrativeAuditRepository organizationAdministrativeAuditRepository(
            JdbcTemplate jdbcTemplate) {
        return new PostgreSqlOrganizationAdministrativeAuditRepository(jdbcTemplate);
    }

    @Bean
    OrganizationTransactionExecutor organizationTransactionExecutor(
            PlatformTransactionManager transactionManager) {
        return new SpringOrganizationTransactionExecutor(transactionManager);
    }

    @Bean
    PlatformOrganizationUseCase platformOrganizationUseCase(
            AuthorizeAdministrativeActionUseCase authorization,
            OrganizationRepository organizations,
            OrganizationLifecycleRepository lifecycle,
            OrganizationTransactionExecutor transactions,
            OrganizationAdministrativeAuditRepository audit) {
        return new PlatformOrganizationService(
                authorization, organizations, lifecycle, transactions, audit,
                java.util.UUID::randomUUID, java.util.UUID::randomUUID);
    }

    @Bean
    OrganizationAdministrationUseCase organizationAdministrationUseCase(
            AuthorizeAdministrativeActionUseCase authorization,
            MutateAdministrativeGrantUseCase grants,
            OrganizationRepository organizations,
            OrganizationTenantPlacementRepository placements,
            FindTenantAdministrativeMetadataUseCase tenants,
            UserExistenceUseCase users,
            OrganizationTransactionExecutor transactions,
            OrganizationAdministrativeAuditRepository audit) {
        return new OrganizationAdministrationService(
                authorization, grants, organizations, placements, tenants, users,
                transactions, audit, java.util.UUID::randomUUID);
    }
}
