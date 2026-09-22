package io.github.piresrenan.orderhub.development;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Declarative, versioned description of the disposable development scenario.
 *
 * <p>Only vocabulary that exists in v1 is used: statuses, permissions, policies and the single v1 governance role are
 * taken from the product code. Client-chosen identifiers (catalog, customers, operations) are deterministic so that
 * examples stay valid across restarts; product-generated identifiers are published by the manifest instead.
 */
final class DevelopmentSeedCatalog {
    static final int SCHEMA_VERSION = 1;
    /** The only Staff role that v1 can create; normal provisioning either assigns it or no role. */
    static final String GOVERNANCE_ROLE = "INITIAL_TENANT_GOVERNANCE_V1";

    // Legacy identifiers kept stable for existing onboarding examples and acceptance tests.
    static final UUID LEGACY_PRODUCT = UUID.fromString("02100000-0000-4000-8000-000000000001");
    static final UUID LEGACY_VARIANT = UUID.fromString("02100000-0000-4000-8000-000000000002");
    static final UUID LEGACY_CUSTOMER = UUID.fromString("02100000-0000-4000-8000-000000000003");
    static final UUID LEGACY_RECEIPT = UUID.fromString("02100000-0000-4000-8000-000000000004");

    /** Semantic development personas; the token subject is always {@code synthetic-local-<name>}. */
    record Persona(String name, String description) { }

    static final List<Persona> PERSONAS = List.of(
            new Persona("platform", "Platform administrator: manages organizations, tenants, placements and organization grants"),
            new Persona("staff", "Tenant alpha governance Staff (initial Staff via cold start)"),
            new Persona("beta-admin", "Tenant beta governance Staff (initial Staff via cold start)"),
            new Persona("multi-tenant-staff", "Governance Staff in gamma (cold start) and alpha (provisioned with the governance role)"),
            new Persona("alpha-member-no-role", "Active alpha Staff membership provisioned without a role: no Tenant authority"),
            new Persona("alpha-suspended", "Alpha Staff whose membership is SUSPENDED"),
            new Persona("alpha-terminated", "Alpha Staff whose membership is TERMINATED"),
            new Persona("org-viewer", "ORGANIZATION_TENANTS_VIEW on organization north only; no Staff anywhere"),
            new Persona("customer", "Customer linked in alpha and beta"),
            new Persona("alpha-customer-2", "Second alpha Customer (another Customer in the same Tenant)"),
            new Persona("beta-customer", "Customer linked only in beta"),
            new Persona("outsider", "Bound User with no membership or grant anywhere"),
            new Persona("unbound", "Verified identity at the local issuer that was never bound to a User"));

    static final List<String> PERSONA_NAMES = PERSONAS.stream().map(value -> value.name()).toList();

    record Organization(String key, String name, List<String> tenants, boolean suspended) { }

    static final List<Organization> ORGANIZATIONS = List.of(
            new Organization("north", "Synthetic North Holdings", List.of("alpha", "beta"), false),
            new Organization("south", "Synthetic South Group", List.of("gamma", "epsilon"), false),
            new Organization("dormant", "Synthetic Dormant Ventures", List.of(), true));

    record Tenant(String key, String name, String inventoryPolicy, boolean suspended) { }

    static final List<Tenant> TENANTS = List.of(
            new Tenant("alpha", "Synthetic local shop", "DENY", false),
            new Tenant("beta", "Synthetic backorder shop", "ALLOW_BACKORDER", false),
            new Tenant("gamma", "Synthetic south shop", "DENY", false),
            new Tenant("delta", "Synthetic suspended shop", null, true),
            new Tenant("epsilon", "Synthetic empty shop", null, false));

    record Category(String key, String slug, String name, String parent) { }

    record Product(String key, String slug, String name, String description, String brand, List<String> categories,
            String status) { }

    record Variant(String key, String product, String sku, String displayName, String mpn,
            Map<String, String> attributes, String status, Map<String, Long> prices) { }

    record Movement(String variant, String type, long quantity, String reason) { }

    record Order(String key, String persona, String customer, Map<String, Integer> items) { }

    record TenantCatalog(List<Category> categories, List<Product> products, List<Variant> variants,
            List<Movement> movements, Map<String, Long> safetyStock, List<Order> orders) { }

    private static final List<Category> CATEGORIES = List.of(
            new Category("office", "office", "Office", null),
            new Category("office-paper", "office-paper", "Office paper", "office"),
            new Category("office-paper-premium", "office-paper-premium", "Premium office paper", "office-paper"),
            new Category("electronics", "electronics", "Electronics", null));

    private static final List<Product> PRODUCTS = List.of(
            new Product("notebook", "synthetic-notebook", "Synthetic notebook", "Disposable demonstration product",
                    "Synthetic", List.of(), "ACTIVE"),
            new Product("paper-ream", "paper-ream", "Paper ream", "Office paper sold per ream", "Synthetic Paper Co",
                    List.of("office-paper"), "ACTIVE"),
            new Product("premium-pen", "premium-pen", "Premium pen", null, null, List.of("office-paper-premium"), "ACTIVE"),
            new Product("standing-desk", "standing-desk", "Standing desk", "Not yet released", "Synthetic Furniture",
                    List.of("office"), "DRAFT"),
            new Product("desk-lamp", "desk-lamp", "Desk lamp", "Retired model", "Synthetic Light", List.of("electronics"),
                    "ARCHIVED"));

    private static final List<Variant> VARIANTS = List.of(
            new Variant("notebook-standard", "notebook", "LOCAL-NOTEBOOK", "Standard", null, Map.of(), "ACTIVE",
                    Map.of("BRL", 1290L)),
            new Variant("paper-a4", "paper-ream", "PAPER-A4", "A4 500 sheets", "SYN-A4-500",
                    Map.of("size", "A4", "sheets", "500"), "ACTIVE", Map.of("BRL", 2590L, "USD", 590L)),
            new Variant("paper-letter", "paper-ream", "PAPER-LETTER", "Letter 500 sheets", null, Map.of("size", "LETTER"),
                    "ACTIVE", Map.of("BRL", 2790L)),
            new Variant("paper-a3", "paper-ream", "PAPER-A3", "A3 250 sheets", null, Map.of("size", "A3"), "INACTIVE",
                    Map.of("BRL", 3990L)),
            new Variant("paper-a5", "paper-ream", "PAPER-A5", "A5 draft", null, Map.of(), "DRAFT", Map.of()),
            new Variant("pen-blue", "premium-pen", "PEN-BLUE", "Blue ink", "SYN-PEN-B", Map.of("color", "blue"), "ACTIVE",
                    Map.of("BRL", 890L)),
            new Variant("desk-standard", "standing-desk", "DESK-STD", "Standard frame", null, Map.of(), "DRAFT", Map.of()),
            new Variant("lamp-old", "desk-lamp", "LAMP-OLD", "Previous generation", null, Map.of(), "ARCHIVED",
                    Map.of("BRL", 4590L)));

    static final Map<String, TenantCatalog> CATALOG = Map.of(
            "alpha", new TenantCatalog(CATEGORIES, PRODUCTS, VARIANTS, List.of(
                    new Movement("notebook-standard", "RECEIPT", 100, "LOCAL_FIXTURE"),
                    new Movement("paper-a4", "RECEIPT", 50, "SEED_RECEIPT"),
                    new Movement("paper-a4", "ADJUSTMENT", -2, "SEED_DAMAGED"),
                    new Movement("paper-letter", "RECEIPT", 20, "SEED_RECEIPT"),
                    new Movement("pen-blue", "RECEIPT", 10, "SEED_RECEIPT"),
                    new Movement("pen-blue", "ADJUSTMENT", -10, "SEED_STOCK_COUNT")),
                    Map.of("paper-a4", 5L),
                    List.of(new Order("alpha-single-item", "customer", "alpha-linked", Map.of("paper-a4", 2)),
                            new Order("alpha-multi-item", "alpha-customer-2", "alpha-second",
                                    Map.of("paper-a4", 1, "paper-letter", 2)))),
            "beta", new TenantCatalog(CATEGORIES, PRODUCTS, VARIANTS, List.of(
                    new Movement("paper-a4", "RECEIPT", 5, "SEED_RECEIPT"),
                    new Movement("paper-letter", "RECEIPT", 20, "SEED_RECEIPT"),
                    new Movement("pen-blue", "RECEIPT", 10, "SEED_RECEIPT"),
                    new Movement("pen-blue", "ADJUSTMENT", -10, "SEED_STOCK_COUNT")),
                    Map.of(),
                    List.of(new Order("beta-partial-backorder", "beta-customer", "beta-linked", Map.of("paper-a4", 8)),
                            new Order("beta-full-backorder", "customer", "beta-linked-shared", Map.of("pen-blue", 1)))),
            "gamma", new TenantCatalog(List.of(CATEGORIES.get(0)),
                    List.of(new Product("paper-ream", "paper-ream", "Paper ream", null, null, List.of("office"), "ACTIVE")),
                    List.of(new Variant("paper-a4", "paper-ream", "PAPER-A4", "A4 500 sheets", null, Map.of(), "ACTIVE",
                            Map.of("BRL", 2490L))),
                    List.of(new Movement("paper-a4", "RECEIPT", 10, "SEED_RECEIPT")), Map.of(), List.of()));

    /** Customer profiles; only the reference row is fixture-owned (v1 has no Customer creation command). */
    record Customer(String key, String tenant, String linkedPersona) { }

    static final List<Customer> CUSTOMERS = List.of(
            new Customer("alpha-linked", "alpha", "customer"),
            new Customer("alpha-second", "alpha", "alpha-customer-2"),
            new Customer("alpha-unlinked", "alpha", null),
            new Customer("beta-linked-shared", "beta", "customer"),
            new Customer("beta-linked", "beta", "beta-customer"),
            new Customer("gamma-unlinked", "gamma", null));

    private DevelopmentSeedCatalog() { }

    /** Deterministic client-chosen identifier; alpha keeps the historical fixed product, variant and customer. */
    static UUID id(String tenant, String kind, String key) {
        if ("alpha".equals(tenant)) {
            if ("product".equals(kind) && "notebook".equals(key)) return LEGACY_PRODUCT;
            if ("variant".equals(kind) && "notebook-standard".equals(key)) return LEGACY_VARIANT;
            if ("customer".equals(kind) && "alpha-linked".equals(key)) return LEGACY_CUSTOMER;
        }
        return UUID.nameUUIDFromBytes(("orderhub-development-seed:" + tenant + ":" + kind + ":" + key)
                .getBytes(StandardCharsets.UTF_8));
    }

    /** Client idempotency key of a seeded Order; replaying it over HTTP with the same body returns the same Order. */
    static String idempotencyKey(String order) {
        return "seed-" + order;
    }
}
