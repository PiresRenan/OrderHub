package io.github.piresrenan.orderhub.catalog.application.port.out;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import io.github.piresrenan.orderhub.catalog.application.port.in.administration.CatalogRevision;
import io.github.piresrenan.orderhub.catalog.application.port.in.administration.CatalogProductSummary;
import io.github.piresrenan.orderhub.catalog.application.port.in.administration.CatalogVariantSummary;
import io.github.piresrenan.orderhub.catalog.domain.model.*;
/** Catalog-owned persistence operations; locking reads require the enclosing administration transaction. */
public interface CatalogAdministrationRepository {
    /** Reads a Tenant-owned Product, optionally taking its administrative write lock. */
    Optional<CatalogRevision<Product>> product(UUID tenantId, UUID id, boolean lock);
    /** Creates a new Product without replacing any existing identity. */
    void insertProduct(Product product);
    /** Changes an expected revision within the caller's authoritative transaction. */
    void updateProduct(Product product, long expectedRevision);
    /** Stabilizes a sellable child before the parent Product lock. */
    Optional<ProductVariant> lockActivationWitness(UUID tenantId, UUID productId);
    /** Persists accountability evidence atomically with the associated mutation. */
    void appendAudit(CatalogAuditEvidence evidence);
    /** Finds only a Tenant-owned Variant, optionally stabilizing it for an administrative write. */
    Optional<CatalogRevision<ProductVariant>> variant(UUID tenantId, UUID id, boolean lock);
    /** Creates an identity once; existing identity is never repurposed. */
    void insertVariant(ProductVariant variant);
    /** Changes only the expected revision of the existing Variant. */
    void updateVariant(ProductVariant variant, long expectedRevision);
    /** Reads the current Category revision; mutations additionally require its Tenant hierarchy guard. */
    Optional<CatalogRevision<Category>> category(UUID tenantId,UUID id);
    /** Advances a Category precondition while its hierarchy guard is held. */
    void advanceCategory(UUID tenantId,UUID id,long expected);
    /** Reads one exact currency price, optionally arbitrating a write. */
    Optional<CatalogRevision<VariantBasePrice>> price(UUID tenantId,UUID variant,String currency,boolean lock);
    /** Creates or changes one price only against the declared prior revision. */
    void savePrice(VariantBasePrice price,long expected);
    /** Bounded single-query discovery projections ordered by PostgreSQL UUID. */
    List<CatalogProductSummary> products(UUID tenant,UUID afterId,int limit);
    /** Bounded Variants within one Tenant-owned Product. */
    List<CatalogVariantSummary> variants(UUID tenant,UUID product,UUID afterId,int limit);
    /** Bounded flat Category nodes, without recursive hierarchy expansion. */
    List<CatalogRevision<Category>> categories(UUID tenant,UUID afterId,int limit);
}
