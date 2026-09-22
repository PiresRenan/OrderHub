package io.github.piresrenan.orderhub.development;

import javax.sql.DataSource;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.github.piresrenan.orderhub.authorization.application.port.in.administration.MutateAdministrativeGrantUseCase;
import io.github.piresrenan.orderhub.catalog.application.service.CatalogAdministrationService;
import io.github.piresrenan.orderhub.catalog.application.service.CatalogCategoryAdministrationService;
import io.github.piresrenan.orderhub.catalog.application.service.CatalogPricingAdministrationService;
import io.github.piresrenan.orderhub.customers.application.port.in.linking.CustomerAccountLinkingUseCase;
import io.github.piresrenan.orderhub.inventory.application.port.in.RecordInventoryMovementUseCase;
import io.github.piresrenan.orderhub.inventory.application.service.InventoryAdministrationReadService;
import io.github.piresrenan.orderhub.inventory.application.service.InventoryPolicyAdministrationService;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateCustomerOrderUseCase;
import io.github.piresrenan.orderhub.organizations.application.port.in.administration.OrganizationAdministrationUseCase;
import io.github.piresrenan.orderhub.organizations.application.port.in.administration.PlatformOrganizationUseCase;
import io.github.piresrenan.orderhub.tenants.application.port.in.administration.PlatformTenantUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveOrCreateExternalUserUseCase;
import io.github.piresrenan.orderhub.workforce.application.port.in.ColdStartStaffProvisioningUseCase;
import io.github.piresrenan.orderhub.workforce.application.port.in.ConsumeStaffProvisioningUseCase;
import io.github.piresrenan.orderhub.workforce.application.port.in.ManageStaffProvisioningUseCase;
import io.github.piresrenan.orderhub.workforce.application.port.in.ManageTenantMembershipUseCase;

/** Explicit import only: Spring Boot excludes @TestConfiguration from ordinary component scanning. */
@TestConfiguration(proxyBeanMethods = false)
public class DevelopmentConfiguration {

    /** Owns the actual pool so alternate Hikari/JNDI configuration cannot redirect fixture connections. */
    @Bean
    com.zaxxer.hikari.HikariDataSource developmentDataSource(PostgreSQLContainer database) {
        var source = new com.zaxxer.hikari.HikariDataSource();
        source.setJdbcUrl(database.getJdbcUrl());
        source.setUsername(database.getUsername());
        source.setPassword(database.getPassword());
        return source;
    }

    /** Seeds once per fresh owned database; a failure aborts startup and disposes the whole fixture. */
    @Bean
    ApplicationRunner developmentSeed(DevelopmentIssuer issuer, PostgreSQLContainer database, DataSource dataSource,
            ResolveOrCreateExternalUserUseCase users, MutateAdministrativeGrantUseCase grants,
            PlatformOrganizationUseCase organizations, OrganizationAdministrationUseCase placements,
            PlatformTenantUseCase tenants, ColdStartStaffProvisioningUseCase coldStart,
            ConsumeStaffProvisioningUseCase staffConsumption, ManageStaffProvisioningUseCase staffProvisioning,
            ManageTenantMembershipUseCase memberships, CatalogAdministrationService catalog,
            CatalogCategoryAdministrationService categories, CatalogPricingAdministrationService pricing,
            InventoryPolicyAdministrationService inventoryPolicy, RecordInventoryMovementUseCase movements,
            InventoryAdministrationReadService inventoryRead, CustomerAccountLinkingUseCase linking,
            CreateCustomerOrderUseCase orders) {
        return args -> {
            try (var connection = dataSource.getConnection()) {
                if (!connection.getMetaData().getURL().equals(database.getJdbcUrl())) {
                    throw new IllegalStateException("Development fixture requires its owned disposable database");
                }
            }
            // Each owner commits its own legitimate transaction before the next authorization snapshot.
            // The owned database is discarded if any later step fails; no incomplete fixture is advertised.
            var boundaries = new DevelopmentSeedScenario.Boundaries(users, grants, organizations, placements, tenants,
                    coldStart, staffConsumption, staffProvisioning, memberships, catalog, categories, pricing,
                    inventoryPolicy, movements, inventoryRead, linking, orders);
            issuer.ready(new DevelopmentSeedScenario(boundaries, issuer, new JdbcTemplate(dataSource)).apply());
        };
    }
}
