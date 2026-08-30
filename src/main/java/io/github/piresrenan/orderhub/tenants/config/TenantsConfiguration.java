package io.github.piresrenan.orderhub.tenants.config;

import java.util.UUID;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.tenants.adapter.out.persistence.postgresql.PostgreSqlTenantRepository;
import io.github.piresrenan.orderhub.tenants.application.port.in.CreateTenantUseCase;
import io.github.piresrenan.orderhub.tenants.application.port.out.TenantIdGenerator;
import io.github.piresrenan.orderhub.tenants.application.port.out.TenantRepository;
import io.github.piresrenan.orderhub.tenants.application.service.CreateTenantService;

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
}
