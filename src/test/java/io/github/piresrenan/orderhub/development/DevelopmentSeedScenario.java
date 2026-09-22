package io.github.piresrenan.orderhub.development;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.authorization.application.port.in.administration.MutateAdministrativeGrantUseCase;
import io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeGrant;
import io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeScope;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.catalog.application.port.in.administration.CatalogAdminContext;
import io.github.piresrenan.orderhub.catalog.application.port.in.administration.CatalogProductMetadata;
import io.github.piresrenan.orderhub.catalog.application.port.in.administration.CatalogVariantMetadata;
import io.github.piresrenan.orderhub.catalog.application.service.CatalogAdministrationService;
import io.github.piresrenan.orderhub.catalog.application.service.CatalogCategoryAdministrationService;
import io.github.piresrenan.orderhub.catalog.application.service.CatalogPricingAdministrationService;
import io.github.piresrenan.orderhub.catalog.domain.model.ProductVariantAttribute;
import io.github.piresrenan.orderhub.customers.application.port.in.linking.CustomerAccountLinkingUseCase;
import io.github.piresrenan.orderhub.customers.application.port.in.linking.CustomerLinkIssuance;
import io.github.piresrenan.orderhub.customers.domain.model.CustomerProfile;
import io.github.piresrenan.orderhub.inventory.application.port.in.InventoryMovementCommand;
import io.github.piresrenan.orderhub.inventory.application.port.in.RecordInventoryMovementUseCase;
import io.github.piresrenan.orderhub.inventory.application.service.InventoryAdministrationReadService;
import io.github.piresrenan.orderhub.inventory.application.service.InventoryPolicyAdministrationService;
import io.github.piresrenan.orderhub.inventory.domain.model.InventoryMovementType;
import io.github.piresrenan.orderhub.inventory.domain.model.InventoryPolicy;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateCustomerOrderUseCase;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderCommand;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderIdempotencyKeyDigest;
import io.github.piresrenan.orderhub.organizations.application.port.in.administration.OrganizationAdministrationUseCase;
import io.github.piresrenan.orderhub.organizations.application.port.in.administration.PlatformOrganizationUseCase;
import io.github.piresrenan.orderhub.tenants.application.port.in.administration.PlatformTenantUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityQuery;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveOrCreateExternalUserUseCase;
import io.github.piresrenan.orderhub.workforce.application.model.IssueStaffProvisioningIntentCommand;
import io.github.piresrenan.orderhub.workforce.application.model.StaffProvisioningIssuance;
import io.github.piresrenan.orderhub.workforce.application.port.in.ColdStartStaffProvisioningUseCase;
import io.github.piresrenan.orderhub.workforce.application.port.in.ConsumeStaffProvisioningUseCase;
import io.github.piresrenan.orderhub.workforce.application.port.in.ManageStaffProvisioningUseCase;
import io.github.piresrenan.orderhub.workforce.application.port.in.ManageTenantMembershipUseCase;

/**
 * Builds {@link DevelopmentSeedCatalog} through the same application use cases the HTTP adapters call.
 *
 * <p>Every step is a separate owner transaction with the real authorization checks of its acting persona; a failure
 * aborts startup before any manifest is published. The only direct writes are the documented CustomerProfile reference
 * rows (v1 has no Customer creation command); the only direct read is the Staff placement selector, which v1 does not
 * expose over HTTP and which clients need for normal Staff provisioning.
 */
final class DevelopmentSeedScenario {
    private static final String CORRELATION = "development-seed";

    /** Application boundaries used by the seed; all are ordinary production beans. */
    record Boundaries(ResolveOrCreateExternalUserUseCase users, MutateAdministrativeGrantUseCase grants,
            PlatformOrganizationUseCase organizations, OrganizationAdministrationUseCase placements,
            PlatformTenantUseCase tenants, ColdStartStaffProvisioningUseCase coldStart,
            ConsumeStaffProvisioningUseCase staffConsumption, ManageStaffProvisioningUseCase staffProvisioning,
            ManageTenantMembershipUseCase memberships, CatalogAdministrationService catalog,
            CatalogCategoryAdministrationService categories, CatalogPricingAdministrationService pricing,
            InventoryPolicyAdministrationService inventoryPolicy, RecordInventoryMovementUseCase movements,
            InventoryAdministrationReadService inventoryRead, CustomerAccountLinkingUseCase linking,
            CreateCustomerOrderUseCase orders) { }

    private final Boundaries boundaries;
    private final DevelopmentIssuer issuer;
    private final JdbcTemplate jdbc;
    private final Map<String, UUID> users = new LinkedHashMap<>();
    private final Map<String, UUID> organizationIds = new LinkedHashMap<>();
    private final Map<String, UUID> tenantIds = new LinkedHashMap<>();
    private final Map<String, String> governance = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> access = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> placements = new LinkedHashMap<>();
    private final List<Map<String, Object>> orders = new ArrayList<>();
    private final List<Map<String, Object>> outstandingProofs = new ArrayList<>();
    private final List<Map<String, Object>> outstandingIntents = new ArrayList<>();

    DevelopmentSeedScenario(Boundaries boundaries, DevelopmentIssuer issuer, JdbcTemplate jdbc) {
        this.boundaries = Objects.requireNonNull(boundaries, "boundaries");
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** Applies the whole scenario and returns the manifest; nothing is published unless every step succeeded. */
    Map<String, Object> apply() {
        var platform = bind("platform");
        // Initial Platform trust is the explicit fixture ceremony using the owner grant/audit primitive.
        for (var permission : List.of(PermissionCode.PLATFORM_TENANTS_MANAGE, PermissionCode.PLATFORM_ORGANIZATIONS_VIEW,
                PermissionCode.PLATFORM_ORGANIZATIONS_MANAGE, PermissionCode.PLATFORM_ORGANIZATION_GRANTS_MANAGE)) {
            boundaries.grants().grant(platform, new AdministrativeGrant(platform, AdministrativeScope.platform(), permission),
                    operation("grant", permission.name()));
        }
        for (var persona : List.of("org-viewer", "customer", "alpha-customer-2", "beta-customer", "outsider")) {
            bind(persona);
        }
        for (var tenant : DevelopmentSeedCatalog.TENANTS) {
            tenantIds.put(tenant.key(), boundaries.tenants().create(platform, tenant.name(), operation("tenant", tenant.key())).id());
        }
        for (var organization : DevelopmentSeedCatalog.ORGANIZATIONS) {
            var id = boundaries.organizations().create(platform, organization.name(), operation("organization", organization.key())).id();
            organizationIds.put(organization.key(), id);
            for (var tenant : organization.tenants()) {
                boundaries.placements().attachTenant(platform, id, tenantIds.get(tenant), operation("attach", tenant));
            }
            if (organization.suspended()) boundaries.organizations().suspend(platform, id, operation("suspend", organization.key()));
        }
        boundaries.grants().grant(platform, new AdministrativeGrant(users.get("org-viewer"),
                AdministrativeScope.organization(organizationIds.get("north")), PermissionCode.ORGANIZATION_TENANTS_VIEW),
                operation("grant", "org-viewer"));

        coldStart(platform, "alpha", "staff");
        coldStart(platform, "beta", "beta-admin");
        coldStart(platform, "gamma", "multi-tenant-staff");
        var alphaStaff = users.get("staff");
        provision("alpha", alphaStaff, "multi-tenant-staff", DevelopmentSeedCatalog.GOVERNANCE_ROLE, "STAFF_GOVERNANCE");
        provision("alpha", alphaStaff, "alpha-member-no-role", null, "STAFF_NO_ROLE");
        provision("alpha", alphaStaff, "alpha-suspended", DevelopmentSeedCatalog.GOVERNANCE_ROLE, "STAFF_SUSPENDED");
        provision("alpha", alphaStaff, "alpha-terminated", DevelopmentSeedCatalog.GOVERNANCE_ROLE, "STAFF_TERMINATED");
        boundaries.memberships().suspend(alphaStaff, tenantIds.get("alpha"), users.get("alpha-suspended"), operation("suspend", "alpha-suspended"));
        boundaries.memberships().terminate(alphaStaff, tenantIds.get("alpha"), users.get("alpha-terminated"), operation("terminate", "alpha-terminated"));
        var pending = (StaffProvisioningIssuance.Issued) boundaries.staffProvisioning().issue(new IssueStaffProvisioningIntentCommand(
                tenantIds.get("alpha"), alphaStaff, (UUID) placements.get("alpha").get("departmentId"),
                (UUID) placements.get("alpha").get("positionId"), null, operation("provision", "pending"), operation("correlation", "pending")));
        outstandingIntents.add(entry("tenant", "alpha", "intentId", pending.intentId(), "initialRoleCode", null));

        var catalogIds = new LinkedHashMap<String, Object>();
        var inventory = new LinkedHashMap<String, Object>();
        for (var tenant : List.of("alpha", "beta", "gamma")) {
            var definition = DevelopmentSeedCatalog.CATALOG.get(tenant);
            var actor = users.get(governance.get(tenant));
            catalogIds.put(tenant, catalog(tenant, actor, definition));
            var policy = DevelopmentSeedCatalog.TENANTS.stream().filter(value -> value.key().equals(tenant)).findFirst().orElseThrow().inventoryPolicy();
            boundaries.inventoryPolicy().policy(actor, tenantIds.get(tenant), null, InventoryPolicy.valueOf(policy), "SEED_POLICY", operation("policy", tenant));
            var index = 0;
            for (var movement : definition.movements()) {
                var operationId = "alpha".equals(tenant) && "notebook-standard".equals(movement.variant())
                        ? DevelopmentSeedCatalog.LEGACY_RECEIPT : DevelopmentSeedCatalog.id(tenant, "movement", String.valueOf(index));
                index++;
                boundaries.movements().record(new InventoryMovementCommand(actor, tenantIds.get(tenant), operationId,
                        DevelopmentSeedCatalog.id(tenant, "variant", movement.variant()), InventoryMovementType.valueOf(movement.type()),
                        movement.quantity(), movement.reason(), operation("movement", tenant + index)));
            }
            for (var safety : new TreeMap<>(definition.safetyStock()).entrySet()) {
                boundaries.inventoryPolicy().safetyStock(actor, tenantIds.get(tenant), DevelopmentSeedCatalog.id(tenant, "variant", safety.getKey()),
                        0, safety.getValue(), "SEED_SAFETY_STOCK", operation("safety", tenant + safety.getKey()));
            }
        }

        var customers = new ArrayList<Map<String, Object>>();
        for (var customer : DevelopmentSeedCatalog.CUSTOMERS) {
            var tenant = tenantIds.get(customer.tenant());
            var profile = new CustomerProfile(tenant, DevelopmentSeedCatalog.id(customer.tenant(), "customer", customer.key()));
            // No Customer creation application command exists in v1. This validated reference row is fixture-only.
            jdbc.update("INSERT INTO customers.customer_profiles (tenant_id, customer_id) VALUES (?, ?)", profile.tenantId(), profile.customerId());
            var staff = users.get(governance.get(customer.tenant()));
            var proof = (CustomerLinkIssuance.Issued) boundaries.linking().issue(staff, tenant, profile.customerId(),
                    operation("customer-proof", customer.key()), operation("customer-correlation", customer.key()));
            if (customer.linkedPersona() != null) {
                boundaries.linking().consume(users.get(customer.linkedPersona()), tenant, proof.credential());
                access.computeIfAbsent(customer.linkedPersona(), key -> new TreeMap<>()).put(customer.tenant(), "CUSTOMER");
            } else if ("alpha".equals(customer.tenant())) {
                outstandingProofs.add(entry("tenant", customer.tenant(), "customer", customer.key(), "proofId", proof.proofId()));
            } else {
                boundaries.linking().cancel(staff, tenant, proof.proofId(), operation("customer-cancel", customer.key()));
            }
            customers.add(entry("key", customer.key(), "tenant", customer.tenant(), "customerId", profile.customerId(),
                    "linkedPersona", customer.linkedPersona()));
        }

        for (var tenant : List.of("alpha", "beta", "gamma")) {
            for (var order : DevelopmentSeedCatalog.CATALOG.get(tenant).orders()) {
                var items = new ArrayList<CreateOrderCommand.Item>();
                var described = new ArrayList<Map<String, Object>>();
                for (var item : new TreeMap<>(order.items()).entrySet()) {
                    items.add(new CreateOrderCommand.Item(DevelopmentSeedCatalog.id(tenant, "variant", item.getKey()), item.getValue()));
                    described.add(entry("variant", item.getKey(), "variantId", DevelopmentSeedCatalog.id(tenant, "variant", item.getKey()),
                            "quantity", item.getValue()));
                }
                var key = DevelopmentSeedCatalog.idempotencyKey(order.key());
                var result = boundaries.orders().create(users.get(order.persona()), new CreateOrderCommand(tenantIds.get(tenant),
                        DevelopmentSeedCatalog.id(tenant, "customer", order.customer()), items, CreateOrderIdempotencyKeyDigest.of(sha256(key))));
                orders.add(entry("key", order.key(), "tenant", tenant, "persona", order.persona(), "customer", order.customer(),
                        "orderId", result.order().id(), "idempotencyKey", key, "items", described,
                        "allocation", result.allocationOutcome().name()));
            }
        }

        for (var tenant : DevelopmentSeedCatalog.TENANTS) {
            if (tenant.suspended()) boundaries.tenants().suspend(platform, tenantIds.get(tenant.key()), operation("suspend", tenant.key()));
        }
        for (var tenant : List.of("alpha", "beta", "gamma")) {
            var actor = users.get(governance.get(tenant));
            var positions = new ArrayList<Map<String, Object>>();
            for (var variant : DevelopmentSeedCatalog.CATALOG.get(tenant).variants()) {
                var id = DevelopmentSeedCatalog.id(tenant, "variant", variant.key());
                if (definitionMoves(tenant, variant.key())) {
                    var position = boundaries.inventoryRead().position(actor, tenantIds.get(tenant), id);
                    positions.add(entry("variant", variant.key(), "variantId", id, "onHand", position.onHand(),
                            "committed", position.committed(), "backordered", position.backordered(), "safetyStock", position.safetyStock()));
                }
            }
            inventory.put(tenant, positions);
        }
        return manifest(catalogIds, inventory, customers);
    }

    private static boolean definitionMoves(String tenant, String variant) {
        return DevelopmentSeedCatalog.CATALOG.get(tenant).movements().stream().anyMatch(value -> value.variant().equals(variant));
    }

    private Map<String, Object> catalog(String tenant, UUID actorUser, DevelopmentSeedCatalog.TenantCatalog definition) {
        var tenantId = tenantIds.get(tenant);
        var actor = new CatalogAdminContext(actorUser, tenantId, CORRELATION);
        var categories = new ArrayList<Map<String, Object>>();
        for (var category : definition.categories()) {
            var id = DevelopmentSeedCatalog.id(tenant, "category", category.key());
            boundaries.categories().create(actor, id, category.parent() == null ? null
                    : DevelopmentSeedCatalog.id(tenant, "category", category.parent()), category.name(), category.slug(), null);
            categories.add(entry("key", category.key(), "id", id, "slug", category.slug(), "parent", category.parent()));
        }
        var products = new ArrayList<Map<String, Object>>();
        var variants = new ArrayList<Map<String, Object>>();
        for (var product : definition.products()) {
            var productId = DevelopmentSeedCatalog.id(tenant, "product", product.key());
            var revision = boundaries.catalog().createProduct(actor, productId, new CatalogProductMetadata(product.name(),
                    product.slug(), product.description(), product.brand())).revision();
            if (!product.categories().isEmpty()) {
                revision = boundaries.catalog().assignCategories(actor, productId, revision, product.categories().stream()
                        .map(value -> DevelopmentSeedCatalog.id(tenant, "category", value)).toList()).revision();
            }
            var owned = definition.variants().stream().filter(value -> value.product().equals(product.key())).toList();
            var variantRevisions = new LinkedHashMap<String, Long>();
            for (var variant : owned) {
                var variantId = DevelopmentSeedCatalog.id(tenant, "variant", variant.key());
                var attributes = new TreeMap<>(variant.attributes()).entrySet().stream()
                        .map(value -> new ProductVariantAttribute(value.getKey(), value.getValue())).toList();
                var variantRevision = boundaries.catalog().createVariant(actor, productId, variantId, new CatalogVariantMetadata(
                        variant.sku(), variant.displayName(), null, variant.mpn(), attributes)).revision();
                for (var price : new TreeMap<>(variant.prices()).entrySet()) {
                    boundaries.pricing().set(actor, variantId, price.getKey(), 0, price.getValue());
                }
                if (!"DRAFT".equals(variant.status())) {
                    variantRevision = boundaries.catalog().activateVariant(actor, variantId, variantRevision).revision();
                }
                variantRevisions.put(variant.key(), variantRevision);
            }
            if (!"DRAFT".equals(product.status())) {
                revision = boundaries.catalog().activateProduct(actor, productId, revision).revision();
            }
            for (var variant : owned) {
                var variantId = DevelopmentSeedCatalog.id(tenant, "variant", variant.key());
                if ("INACTIVE".equals(variant.status())) {
                    boundaries.catalog().deactivateVariant(actor, variantId, variantRevisions.get(variant.key()));
                } else if ("ARCHIVED".equals(variant.status())) {
                    boundaries.catalog().archiveVariant(actor, variantId, variantRevisions.get(variant.key()));
                }
                variants.add(entry("key", variant.key(), "id", variantId, "product", product.key(), "sku", variant.sku(),
                        "status", variant.status(), "prices", new TreeMap<>(variant.prices())));
            }
            if ("ARCHIVED".equals(product.status())) {
                boundaries.catalog().archiveProduct(actor, productId, revision);
            }
            products.add(entry("key", product.key(), "id", productId, "slug", product.slug(), "status", product.status(),
                    "categories", product.categories()));
        }
        return entry("categories", categories, "products", products, "variants", variants);
    }

    private void coldStart(UUID platform, String tenant, String persona) {
        var issued = (StaffProvisioningIssuance.Issued) boundaries.coldStart().issue(platform, tenantIds.get(tenant),
                operation("cold-start", tenant), operation("cold-start-correlation", tenant));
        boundaries.staffConsumption().consume(issued.credential(), issuer.baseUri(), DevelopmentIssuer.subject(persona));
        users.put(persona, resolve(persona));
        governance.put(tenant, persona);
        access.computeIfAbsent(persona, key -> new TreeMap<>()).put(tenant, "STAFF_GOVERNANCE");
        // v1 exposes no read of the governance placement; clients need these selectors to provision further Staff.
        var placement = jdbc.queryForMap("SELECT department_id, position_id FROM workforce.staff_placements WHERE tenant_id = ?",
                tenantIds.get(tenant));
        placements.put(tenant, entry("departmentId", placement.get("department_id"), "positionId", placement.get("position_id")));
    }

    private void provision(String tenant, UUID issuedBy, String persona, String role, String resultingAccess) {
        var placement = placements.get(tenant);
        var issued = (StaffProvisioningIssuance.Issued) boundaries.staffProvisioning().issue(new IssueStaffProvisioningIntentCommand(
                tenantIds.get(tenant), issuedBy, (UUID) placement.get("departmentId"), (UUID) placement.get("positionId"), role,
                operation("provision", persona), operation("provision-correlation", persona)));
        boundaries.staffConsumption().consume(issued.credential(), issuer.baseUri(), DevelopmentIssuer.subject(persona));
        users.put(persona, resolve(persona));
        access.computeIfAbsent(persona, key -> new TreeMap<>()).put(tenant, resultingAccess);
    }

    private UUID bind(String persona) {
        var user = boundaries.users().resolveOrCreate(new ResolveExternalIdentityQuery(issuer.baseUri(),
                DevelopmentIssuer.subject(persona))).userId();
        users.put(persona, user);
        return user;
    }

    private UUID resolve(String persona) {
        return boundaries.users().resolveOrCreate(new ResolveExternalIdentityQuery(issuer.baseUri(), DevelopmentIssuer.subject(persona))).userId();
    }

    private Map<String, Object> manifest(Map<String, Object> catalog, Map<String, Object> inventory, List<Map<String, Object>> customers) {
        var personas = new ArrayList<Map<String, Object>>();
        for (var persona : DevelopmentSeedCatalog.PERSONAS) {
            var tenantAccess = new TreeMap<String, String>(access.getOrDefault(persona.name(), Map.of()));
            if ("unbound".equals(persona.name())) {
                DevelopmentSeedCatalog.TENANTS.forEach(tenant -> tenantAccess.put(tenant.key(), "UNBOUND"));
            }
            var platform = "platform".equals(persona.name()) ? List.of("PLATFORM_ORGANIZATIONS_MANAGE", "PLATFORM_ORGANIZATIONS_VIEW",
                    "PLATFORM_ORGANIZATION_GRANTS_MANAGE", "PLATFORM_TENANTS_MANAGE") : List.<String>of();
            var organization = "org-viewer".equals(persona.name())
                    ? List.of(entry("organization", "north", "permission", "ORGANIZATION_TENANTS_VIEW")) : List.<Map<String, Object>>of();
            personas.add(entry("name", persona.name(), "subject", DevelopmentIssuer.subject(persona.name()),
                    "userId", users.get(persona.name()), "description", persona.description(), "tenantAccess", tenantAccess,
                    "platformPermissions", platform, "organizationPermissions", organization));
        }
        var organizations = new ArrayList<Map<String, Object>>();
        for (var organization : DevelopmentSeedCatalog.ORGANIZATIONS) {
            organizations.add(entry("key", organization.key(), "id", organizationIds.get(organization.key()), "name", organization.name(),
                    "status", organization.suspended() ? "SUSPENDED" : "ACTIVE", "tenants", organization.tenants()));
        }
        var tenants = new ArrayList<Map<String, Object>>();
        for (var tenant : DevelopmentSeedCatalog.TENANTS) {
            var owner = DevelopmentSeedCatalog.ORGANIZATIONS.stream().filter(value -> value.tenants().contains(tenant.key()))
                    .map(value -> value.key()).findFirst().orElse(null);
            tenants.add(entry("key", tenant.key(), "id", tenantIds.get(tenant.key()), "name", tenant.name(),
                    "status", tenant.suspended() ? "SUSPENDED" : "ACTIVE", "organization", owner,
                    "inventoryPolicy", tenant.inventoryPolicy(), "governanceStaff", governance.get(tenant.key()),
                    "governanceRoleCode", governance.containsKey(tenant.key()) ? DevelopmentSeedCatalog.GOVERNANCE_ROLE : null,
                    "staffPlacement", placements.get(tenant.key())));
        }
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("schemaVersion", DevelopmentSeedCatalog.SCHEMA_VERSION);
        manifest.put("issuer", issuer.baseUri());
        manifest.put("tenantId", tenantIds.get("alpha"));
        manifest.put("productId", DevelopmentSeedCatalog.LEGACY_PRODUCT);
        manifest.put("variantId", DevelopmentSeedCatalog.LEGACY_VARIANT);
        manifest.put("customerId", DevelopmentSeedCatalog.LEGACY_CUSTOMER);
        manifest.put("personas", personas);
        manifest.put("organizations", organizations);
        manifest.put("tenants", tenants);
        manifest.put("customers", customers);
        manifest.put("catalog", catalog);
        manifest.put("inventory", inventory);
        manifest.put("orders", orders);
        manifest.put("outstanding", entry("customerAccountLinkProofs", outstandingProofs, "staffProvisioningIntents", outstandingIntents));
        return manifest;
    }

    /** Deterministic operation/correlation identifier so owner idempotency evidence is stable across restarts. */
    private static UUID operation(String kind, String key) {
        return DevelopmentSeedCatalog.id("operation", kind, key);
    }

    /** Ordered map that tolerates absent (null) values, which the manifest publishes explicitly. */
    private static Map<String, Object> entry(Object... pairs) {
        var map = new LinkedHashMap<String, Object>();
        for (var index = 0; index < pairs.length; index += 2) {
            map.put((String) pairs[index], pairs[index + 1]);
        }
        return map;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
