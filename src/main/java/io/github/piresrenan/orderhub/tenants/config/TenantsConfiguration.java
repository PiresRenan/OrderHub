package io.github.piresrenan.orderhub.tenants.config;

import java.util.UUID;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import io.github.piresrenan.orderhub.authorization.application.port.in.administration.AuthorizeAdministrativeActionUseCase;
import io.github.piresrenan.orderhub.tenants.adapter.out.persistence.postgresql.PostgreSqlTenantAdministrativeAuditRepository;
import io.github.piresrenan.orderhub.tenants.adapter.out.persistence.postgresql.PostgreSqlTenantLifecycleRepository;
import io.github.piresrenan.orderhub.tenants.adapter.out.persistence.postgresql.PostgreSqlTenantRepository;
import io.github.piresrenan.orderhub.tenants.adapter.out.transaction.spring.SpringTenantTransactionExecutor;
import io.github.piresrenan.orderhub.tenants.application.port.in.CreateTenantUseCase;
import io.github.piresrenan.orderhub.tenants.application.port.in.administration.PlatformTenantUseCase;
import io.github.piresrenan.orderhub.tenants.application.port.in.administration.FindTenantAdministrativeMetadataUseCase;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.FindTenantOperationalStateUseCase;
import io.github.piresrenan.orderhub.tenants.application.port.out.TenantIdGenerator;
import io.github.piresrenan.orderhub.tenants.application.port.out.TenantAdministrativeAuditRepository;
import io.github.piresrenan.orderhub.tenants.application.port.out.TenantLifecycleRepository;
import io.github.piresrenan.orderhub.tenants.application.port.out.TenantRepository;
import io.github.piresrenan.orderhub.tenants.application.port.out.TenantTransactionExecutor;
import io.github.piresrenan.orderhub.tenants.application.service.CreateTenantService;
import io.github.piresrenan.orderhub.tenants.application.service.FindTenantOperationalStateService;
import io.github.piresrenan.orderhub.tenants.application.service.FindTenantAdministrativeMetadataService;
import io.github.piresrenan.orderhub.tenants.application.service.TenantAdministrationService;

@Configuration(proxyBeanMethods = false)
public class TenantsConfiguration {

    /**
     * Provides the durable PostgreSQL implementation of the TenantRepository
     * output port.
     *
     * <p>
     * Tenant currently maps to one relational row, so the adapter requires only
     * configured JDBC operations and does not introduce an explicit transaction
     * abstraction.
     * </p>
     *
     * @param jdbcTemplate configured JDBC operations for the application
     *                     DataSource
     * @return PostgreSQL-backed Tenant repository
     */
    @Bean
    TenantRepository tenantRepository(
            JdbcTemplate jdbcTemplate) {

        return new PostgreSqlTenantRepository(
                jdbcTemplate);
    }

    /**
     * Provides the production identity-generation strategy for new Tenant
     * aggregates.
     *
     * @return generator backed by UUID.randomUUID
     */
    @Bean
    TenantIdGenerator tenantIdGenerator() {
        return UUID::randomUUID;
    }

    /**
     * Composes the Tenant creation use case with its application-owned output
     * ports.
     *
     * <p>
     * Framework composition remains here so CreateTenantService stays independent
     * of Spring annotations and infrastructure types.
     * </p>
     *
     * @param tenantRepository configured Tenant persistence port
     * @param tenantIdGenerator configured Tenant identity-generation port
     * @return application input port ready to create Tenant aggregates
     */
    @Bean
    CreateTenantUseCase createTenantUseCase(
            TenantRepository tenantRepository,
            TenantIdGenerator tenantIdGenerator) {

        return new CreateTenantService(
                tenantRepository,
                tenantIdGenerator);
    }

    /**
     * Exposes the bounded Tenant operational-state application contract.
     *
     * @param tenantRepository Tenant-owned durable source of operational state
     * @return operational-state lookup boundary
     */
    @Bean
    FindTenantOperationalStateUseCase findTenantOperationalStateUseCase(
            TenantRepository tenantRepository) {

        return new FindTenantOperationalStateService(
                tenantRepository);
    }

    @Bean
    TenantLifecycleRepository tenantLifecycleRepository(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager) {
        return new PostgreSqlTenantLifecycleRepository(
                jdbcTemplate, transactionManager);
    }

    @Bean
    TenantAdministrativeAuditRepository tenantAdministrativeAuditRepository(
            JdbcTemplate jdbcTemplate) {
        return new PostgreSqlTenantAdministrativeAuditRepository(jdbcTemplate);
    }

    @Bean
    TenantTransactionExecutor tenantTransactionExecutor(
            PlatformTransactionManager transactionManager) {
        return new SpringTenantTransactionExecutor(transactionManager);
    }

    @Bean
    PlatformTenantUseCase platformTenantUseCase(
            AuthorizeAdministrativeActionUseCase authorization,
            TenantRepository tenantRepository,
            TenantLifecycleRepository tenantLifecycleRepository,
            TenantTransactionExecutor tenantTransactionExecutor,
            TenantAdministrativeAuditRepository tenantAdministrativeAuditRepository) {
        return new TenantAdministrationService(
                authorization,
                tenantRepository,
                tenantLifecycleRepository,
                tenantTransactionExecutor,
                tenantAdministrativeAuditRepository,
                UUID::randomUUID,
                UUID::randomUUID);
    }

    @Bean
    FindTenantAdministrativeMetadataUseCase findTenantAdministrativeMetadataUseCase(
            TenantRepository tenantRepository) {
        return new FindTenantAdministrativeMetadataService(tenantRepository);
    }
}
