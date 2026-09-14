package io.github.piresrenan.orderhub.development;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.github.piresrenan.orderhub.authorization.application.port.in.administration.MutateAdministrativeGrantUseCase;
import io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeGrant;
import io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeScope;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.catalog.application.port.in.administration.CatalogAdminContext;
import io.github.piresrenan.orderhub.catalog.application.port.in.administration.CatalogProductMetadata;
import io.github.piresrenan.orderhub.catalog.application.port.in.administration.CatalogVariantMetadata;
import io.github.piresrenan.orderhub.catalog.application.service.CatalogAdministrationService;
import io.github.piresrenan.orderhub.catalog.application.service.CatalogPricingAdministrationService;
import io.github.piresrenan.orderhub.customers.application.port.in.linking.CustomerAccountLinkingUseCase;
import io.github.piresrenan.orderhub.customers.application.port.in.linking.CustomerLinkIssuance;
import io.github.piresrenan.orderhub.customers.domain.model.CustomerProfile;
import io.github.piresrenan.orderhub.inventory.application.port.in.InventoryMovementCommand;
import io.github.piresrenan.orderhub.inventory.application.port.in.RecordInventoryMovementUseCase;
import io.github.piresrenan.orderhub.inventory.application.service.InventoryPolicyAdministrationService;
import io.github.piresrenan.orderhub.inventory.domain.model.InventoryMovementType;
import io.github.piresrenan.orderhub.inventory.domain.model.InventoryPolicy;
import io.github.piresrenan.orderhub.tenants.application.port.in.administration.PlatformTenantUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityQuery;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveOrCreateExternalUserUseCase;
import io.github.piresrenan.orderhub.workforce.application.model.StaffProvisioningIssuance;
import io.github.piresrenan.orderhub.workforce.application.port.in.ColdStartStaffProvisioningUseCase;
import io.github.piresrenan.orderhub.workforce.application.port.in.ConsumeStaffProvisioningUseCase;

/** Explicit import only: Spring Boot excludes @TestConfiguration from ordinary component scanning. */
@TestConfiguration(proxyBeanMethods = false)
public class DevelopmentConfiguration {
    private static final UUID PRODUCT = UUID.fromString("02100000-0000-4000-8000-000000000001");
    private static final UUID VARIANT = UUID.fromString("02100000-0000-4000-8000-000000000002");
    private static final UUID CUSTOMER = UUID.fromString("02100000-0000-4000-8000-000000000003");

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
            ResolveOrCreateExternalUserUseCase users,
            MutateAdministrativeGrantUseCase grants, PlatformTenantUseCase tenants,
            ColdStartStaffProvisioningUseCase coldStart, ConsumeStaffProvisioningUseCase staff,
            CatalogAdministrationService catalog, CatalogPricingAdministrationService pricing,
            InventoryPolicyAdministrationService inventoryPolicy, RecordInventoryMovementUseCase movements,
            CustomerAccountLinkingUseCase linking) {
        return args -> {
            try (var connection = dataSource.getConnection()) {
                if (!connection.getMetaData().getURL().equals(database.getJdbcUrl())) {
                    throw new IllegalStateException("Development fixture requires its owned disposable database");
                }
            }
            var jdbc = new JdbcTemplate(dataSource);
            // Each owner commits its own legitimate transaction before the next authorization snapshot.
            // The owned database is discarded if any later step fails; no incomplete fixture is advertised.
            var platform = user(users, issuer, "platform");
            // Initial trust is an explicit fixture ceremony using the existing atomic owner grant/audit primitive.
            for (var permission : List.of(PermissionCode.PLATFORM_TENANTS_MANAGE,
                    PermissionCode.PLATFORM_ORGANIZATIONS_VIEW, PermissionCode.PLATFORM_ORGANIZATIONS_MANAGE,
                    PermissionCode.PLATFORM_ORGANIZATION_GRANTS_MANAGE)) {
                grants.grant(platform, new AdministrativeGrant(platform, AdministrativeScope.platform(), permission), UUID.randomUUID());
            }
            var tenant = tenants.create(platform, "Synthetic local shop", UUID.randomUUID()).id();
            var invitation = (StaffProvisioningIssuance.Issued) coldStart.issue(platform, tenant, UUID.randomUUID(), UUID.randomUUID());
            staff.consume(invitation.credential(), issuer.baseUri(), DevelopmentIssuer.subject("staff"));
            var staffUser = user(users, issuer, "staff");
            var customerUser = user(users, issuer, "customer");
            user(users, issuer, "outsider");
            var actor = new CatalogAdminContext(staffUser, tenant, "local-fixture");
            catalog.createProduct(actor, PRODUCT, new CatalogProductMetadata("Synthetic notebook", "synthetic-notebook", "Disposable demonstration product", "Synthetic"));
            catalog.createVariant(actor, PRODUCT, VARIANT, new CatalogVariantMetadata("LOCAL-NOTEBOOK", "Standard", null, null, List.of()));
            catalog.activateVariant(actor, VARIANT, 1);
            catalog.activateProduct(actor, PRODUCT, 1);
            pricing.set(actor, VARIANT, "BRL", 0, 1290);
            inventoryPolicy.policy(staffUser, tenant, null, InventoryPolicy.DENY, "LOCAL_FIXTURE", UUID.randomUUID());
            movements.record(new InventoryMovementCommand(staffUser, tenant,
                    UUID.fromString("02100000-0000-4000-8000-000000000004"), VARIANT,
                    InventoryMovementType.RECEIPT, 100, "LOCAL_FIXTURE", UUID.randomUUID()));
            // No Customer creation application command exists. This validated reference row is fixture-only.
            var customer = new CustomerProfile(tenant, CUSTOMER);
            jdbc.update("INSERT INTO customers.customer_profiles (tenant_id, customer_id) VALUES (?, ?)", customer.tenantId(), customer.customerId());
            var proof = (CustomerLinkIssuance.Issued) linking.issue(staffUser, tenant, CUSTOMER, UUID.randomUUID(), UUID.randomUUID());
            linking.consume(customerUser, tenant, proof.credential());
            var selectors = Map.<String, Object>of("tenantId", tenant, "productId", PRODUCT, "variantId", VARIANT,
                        "customerId", CUSTOMER, "personas", List.of("platform", "staff", "customer", "outsider"));
            issuer.ready(selectors);
        };
    }

    private static UUID user(ResolveOrCreateExternalUserUseCase users, DevelopmentIssuer issuer, String persona) {
        return users.resolveOrCreate(new ResolveExternalIdentityQuery(issuer.baseUri(), DevelopmentIssuer.subject(persona))).userId();
    }
}
