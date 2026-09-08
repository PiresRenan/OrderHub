package io.github.piresrenan.orderhub.catalog.application.service;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import io.github.piresrenan.orderhub.catalog.application.port.in.administration.*;
import io.github.piresrenan.orderhub.catalog.application.port.out.*;
import io.github.piresrenan.orderhub.catalog.domain.model.*;
/** Explicit administrative transitions; authorization always precedes target reads. */
public final class CatalogAdministrationService {
    private final CatalogAdminAuthorizer authorizer;
    private final CatalogAdministrationRepository repository;
    private final CatalogAdminTransactionExecutor transactions;
    private final Clock clock;
    /** Requires the owner ports that arbitrate authorization, persistence and atomic evidence. */
    public CatalogAdministrationService(CatalogAdminAuthorizer authorizer, CatalogAdministrationRepository repository,
            CatalogAdminTransactionExecutor transactions, Clock clock) {
        this.authorizer=Objects.requireNonNull(authorizer); this.repository=Objects.requireNonNull(repository);
        this.transactions=Objects.requireNonNull(transactions); this.clock=Objects.requireNonNull(clock);
    }
    /** Creates only a DRAFT Product; creation cannot replace an existing identity. */
    public CatalogRevision<Product> createProduct(CatalogAdminContext actor, UUID id, CatalogProductMetadata input) {
        authorizer.require(actor, CatalogAdminPermission.MANAGE);
        var product=Product.create(id,actor.tenantId(),input.name(),input.slug(),input.description(),input.brand(),List.of());
        return transactions.execute(() -> {
            repository.insertProduct(product);
            audit(actor,id,"PRODUCT_CREATED",0,1);
            return new CatalogRevision<>(product,1);
        });
    }
    /** Changes bounded metadata while preserving the current lifecycle and assignments. */
    public CatalogRevision<Product> updateProduct(CatalogAdminContext actor, UUID id, long expectedRevision,
            CatalogProductMetadata input) {
        authorizer.require(actor, CatalogAdminPermission.MANAGE);
        return transactions.execute(() -> mutateProduct(actor,id,expectedRevision,"PRODUCT_METADATA_CHANGED",
            old -> Product.rehydrate(old.id(),old.tenantId(),input.name(),input.slug(),input.description(),
                    input.brand(),old.categoryIds(),old.status())));
    }
    /** Replaces a bounded set of classifications against the current Product revision. */
    public CatalogRevision<Product> assignCategories(CatalogAdminContext actor, UUID id, long expected,
            List<UUID> categoryIds) {
        authorizer.require(actor,CatalogAdminPermission.MANAGE);
        if(categoryIds==null || categoryIds.size()>100) throw new IllegalArgumentException("Invalid category assignments");
        var requested=List.copyOf(categoryIds);
        return transactions.execute(() -> mutateProduct(actor,id,expected,"PRODUCT_CATEGORIES_CHANGED",old -> {
            for(var categoryId:requested) {
                repository.category(actor.tenantId(),categoryId).orElseThrow(CatalogAdminNotFoundException::new);
            }
            return Product.rehydrate(old.id(),old.tenantId(),old.name(),old.slug(),old.description(),old.brand(),requested,old.status());
        }));
    }
    /** Requires a stable eligible Variant before Product activation. */
    public CatalogRevision<Product> activateProduct(CatalogAdminContext actor, UUID id, long expectedRevision) {
        authorizer.require(actor,CatalogAdminPermission.MANAGE);
        return transactions.execute(() -> {
            // One stable sellable witness is sufficient; acquire Variant before Product.
            var witness=repository.lockActivationWitness(actor.tenantId(),id).orElseThrow(CatalogAdminConflictException::new);
            return mutateProduct(actor,id,expectedRevision,"PRODUCT_ACTIVATED",old -> old.activate(List.of(witness)));
        });
    }
    /** Reads administrative Product data only after its explicit read permission. */
    public CatalogRevision<Product> product(CatalogAdminContext actor, UUID id) {
        authorizer.require(actor,CatalogAdminPermission.VIEW);
        return transactions.execute(() -> repository.product(actor.tenantId(),id,false)
                .orElseThrow(CatalogAdminNotFoundException::new));
    }
    /** Reads administrative Variant data only after its explicit read permission. */
    public CatalogRevision<ProductVariant> variant(CatalogAdminContext actor, UUID id) {
        authorizer.require(actor,CatalogAdminPermission.VIEW);
        return transactions.execute(() -> repository.variant(actor.tenantId(),id,false)
                .orElseThrow(CatalogAdminNotFoundException::new));
    }
    /** Retires a Product without deleting or reusing its commercial identity. */
    public CatalogRevision<Product> archiveProduct(CatalogAdminContext actor, UUID id, long expectedRevision) {
        authorizer.require(actor,CatalogAdminPermission.MANAGE);
        return transactions.execute(() -> mutateProduct(actor,id,expectedRevision,"PRODUCT_ARCHIVED",
                old -> Product.rehydrate(old.id(),old.tenantId(),old.name(),old.slug(),old.description(),
                        old.brand(),old.categoryIds(),ProductStatus.ARCHIVED)));
    }
    /** Creates a DRAFT sellable-unit identity only below an existing Tenant-owned Product. */
    public CatalogRevision<ProductVariant> createVariant(CatalogAdminContext actor, UUID productId, UUID id,
            CatalogVariantMetadata input) {
        authorizer.require(actor,CatalogAdminPermission.MANAGE);
        var variant=ProductVariant.create(id,actor.tenantId(),productId,input.sku(),input.displayName(),
                input.gtin(),input.mpn(),input.attributes());
        return transactions.execute(() -> {
            repository.product(actor.tenantId(),productId,false).orElseThrow(CatalogAdminNotFoundException::new);
            repository.insertVariant(variant);
            audit(actor,id,"VARIANT_CREATED",0,1);
            return new CatalogRevision<>(variant,1);
        });
    }
    /** Updates metadata without changing parent, identity or lifecycle. */
    public CatalogRevision<ProductVariant> updateVariant(CatalogAdminContext actor, UUID id, long expected,
            CatalogVariantMetadata input) {
        authorizer.require(actor,CatalogAdminPermission.MANAGE);
        return transactions.execute(() -> mutateVariant(actor,id,expected,"VARIANT_METADATA_CHANGED",
                old -> ProductVariant.rehydrate(old.id(),old.tenantId(),old.productId(),input.sku(),
                        input.displayName(),input.gtin(),input.mpn(),input.attributes(),old.status())));
    }
    /** Makes a nonarchived Variant eligible for new business subject to Product eligibility. */
    public CatalogRevision<ProductVariant> activateVariant(CatalogAdminContext actor, UUID id, long expected) {
        authorizer.require(actor,CatalogAdminPermission.MANAGE);
        return transactions.execute(() -> mutateVariant(actor,id,expected,"VARIANT_ACTIVATED",ProductVariant::activate));
    }
    /** Temporarily removes an active Variant from new business. */
    public CatalogRevision<ProductVariant> deactivateVariant(CatalogAdminContext actor, UUID id, long expected) {
        authorizer.require(actor,CatalogAdminPermission.MANAGE);
        return transactions.execute(() -> mutateVariant(actor,id,expected,"VARIANT_DEACTIVATED",ProductVariant::deactivate));
    }
    /** Retires the Variant while preserving historical references. */
    public CatalogRevision<ProductVariant> archiveVariant(CatalogAdminContext actor, UUID id, long expected) {
        authorizer.require(actor,CatalogAdminPermission.MANAGE);
        return transactions.execute(() -> mutateVariant(actor,id,expected,"VARIANT_ARCHIVED",ProductVariant::archive));
    }
    /** Serializes a Variant transition and its evidence against the expected revision. */
    private CatalogRevision<ProductVariant> mutateVariant(CatalogAdminContext actor, UUID id, long expected,
            String action, Function<ProductVariant,ProductVariant> change) {
        var current=repository.variant(actor.tenantId(),id,true).orElseThrow(CatalogAdminNotFoundException::new);
        requireRevision(current.revision(),expected);
        final ProductVariant changed;
        try { changed=change.apply(current.value()); }
        catch(IllegalStateException exception) { throw new CatalogAdminConflictException(); }
        repository.updateVariant(changed,expected);
        audit(actor,id,action,expected,expected+1);
        return new CatalogRevision<>(changed,expected+1);
    }
    /** Applies one Product transition against its locked current revision. */
    private CatalogRevision<Product> mutateProduct(CatalogAdminContext actor,UUID id,long expected,String action,
            Function<Product,Product> change) {
        var current=repository.product(actor.tenantId(),id,true).orElseThrow(CatalogAdminNotFoundException::new);
        requireRevision(current.revision(),expected);
        final Product changed;
        try { changed=change.apply(current.value()); }
        catch(IllegalStateException e) { throw new CatalogAdminConflictException(); }
        repository.updateProduct(changed,expected);
        audit(actor,id,action,expected,expected+1);
        return new CatalogRevision<>(changed,expected+1);
    }
    /** Records only accountability and revision facts inside the authoritative transaction. */
    private void audit(CatalogAdminContext actor,UUID id,String action,long before,long after) {
        repository.appendAudit(new CatalogAuditEvidence(UUID.randomUUID(),actor.tenantId(),actor.userId(),id,
                action,before,after,actor.correlationId(),clock.instant()));
    }
    /** Rejects stale preconditions and revision overflow before any durable mutation. */
    private static void requireRevision(long actual,long expected) {
        if(expected<1 || actual!=expected || actual==Long.MAX_VALUE) throw new CatalogAdminConflictException();
    }
}
