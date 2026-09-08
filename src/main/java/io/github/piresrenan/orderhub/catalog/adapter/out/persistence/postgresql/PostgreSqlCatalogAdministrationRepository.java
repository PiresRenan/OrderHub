package io.github.piresrenan.orderhub.catalog.adapter.out.persistence.postgresql;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;
import io.github.piresrenan.orderhub.catalog.application.port.in.administration.*;
import io.github.piresrenan.orderhub.catalog.application.port.out.*;
import io.github.piresrenan.orderhub.catalog.domain.model.*;

/** Owner-local revision arbitration reuses existing aggregate persistence after row stabilization. */
public final class PostgreSqlCatalogAdministrationRepository implements CatalogAdministrationRepository {
    private final JdbcTemplate jdbc;
    private final PostgreSqlProductRepository products;
    private final PostgreSqlProductVariantRepository variants;
    /** Shares the caller's transaction with the established aggregate adapters. */
    public PostgreSqlCatalogAdministrationRepository(JdbcTemplate jdbc, TransactionOperations transactions) {
        this.jdbc=java.util.Objects.requireNonNull(jdbc);
        products=new PostgreSqlProductRepository(jdbc,transactions);
        variants=new PostgreSqlProductVariantRepository(jdbc,transactions);
    }
    /** Reads the root revision and optional write lock before rehydrating its associations. */
    @Override public Optional<CatalogRevision<Product>> product(UUID tenant,UUID id,boolean lock) {
        return revision("catalog.products",tenant,id,lock).map(revision ->
                new CatalogRevision<>(products.findById(tenant,id).orElseThrow(CatalogAdminNotFoundException::new),revision));
    }
    /** Reads one Variant and its attributes inside the owner transaction. */
    @Override public Optional<CatalogRevision<ProductVariant>> variant(UUID tenant,UUID id,boolean lock) {
        return revision("catalog.product_variants",tenant,id,lock).map(revision ->
                new CatalogRevision<>(variants.findById(tenant,id).orElseThrow(CatalogAdminNotFoundException::new),revision));
    }
    /** Uses only hardcoded owner table names; values remain bound parameters. */
    private Optional<Long> revision(String table,UUID tenant,UUID id,boolean lock) {
        return jdbc.query("SELECT revision FROM "+table+" WHERE tenant_id=? AND id=?"+(lock?" FOR UPDATE":""),
                (row,n)->row.getLong(1),tenant,id).stream().findFirst();
    }
    /** Insert-only creation prevents a caller from repurposing existing identity. */
    @Override public void insertProduct(Product product) {
        try {
            jdbc.update("INSERT INTO catalog.products(tenant_id,id,name,slug,description,brand,status) VALUES (?,?,?,?,?,?,?)",
                    product.tenantId(),product.id(),product.name(),product.slug(),product.description(),product.brand(),product.status().name());
        } catch(DuplicateKeyException exception) { throw new CatalogAdminConflictException(); }
    }
    /** Creates the root once, then reuses attribute persistence in the same transaction. */
    @Override public void insertVariant(ProductVariant variant) {
        try {
            jdbc.update("""
                    INSERT INTO catalog.product_variants(tenant_id,id,product_id,sku,display_name,gtin,mpn,status)
                    VALUES (?,?,?,?,?,?,?,?)
                    """,variant.tenantId(),variant.id(),variant.productId(),variant.sku(),variant.displayName(),
                    variant.gtin(),variant.mpn(),variant.status().name());
            variants.save(variant);
        } catch(DuplicateKeyException exception) { throw new CatalogAdminConflictException(); }
    }
    /** Advances exactly one root revision before saving its already stabilized snapshot. */
    @Override public void updateProduct(Product product,long expected) {
        advance("catalog.products",product.tenantId(),product.id(),expected);
        try { products.save(product); }
        catch(CatalogPersistenceException exception) { throw translateUniqueConflict(exception); }
    }
    /** Advances exactly one Variant revision without changing its immutable parent. */
    @Override public void updateVariant(ProductVariant variant,long expected) {
        advance("catalog.product_variants",variant.tenantId(),variant.id(),expected);
        try { variants.save(variant); }
        catch(CatalogPersistenceException exception) { throw translateUniqueConflict(exception); }
    }
    /** Only merchant-key uniqueness is a conflict; other persistence failures remain technical. */
    private RuntimeException translateUniqueConflict(CatalogPersistenceException exception) {
        return exception.getCause() instanceof DuplicateKeyException ? new CatalogAdminConflictException() : exception;
    }
    /** Conditional arithmetic rejects stale revisions and exhausted counters. */
    private void advance(String table,UUID tenant,UUID id,long expected) {
        if(jdbc.update("UPDATE "+table+" SET revision=revision+1 WHERE tenant_id=? AND id=? AND revision=? AND revision<9223372036854775807",
                tenant,id,expected)!=1) throw new CatalogAdminConflictException();
    }
    /** Stabilizes one ACTIVE Variant before the Product lock, preserving global acquisition order. */
    @Override public Optional<ProductVariant> lockActivationWitness(UUID tenant,UUID productId) {
        return jdbc.query("""
                SELECT id FROM catalog.product_variants WHERE tenant_id=? AND product_id=? AND status='ACTIVE'
                ORDER BY id LIMIT 1 FOR SHARE
                """,(row,n)->row.getObject(1,UUID.class),tenant,productId).stream().findFirst()
                .flatMap(id->variants.findById(tenant,id));
    }
    /** Appends evidence to the same JDBC transaction as the authoritative write. */
    @Override public void appendAudit(CatalogAuditEvidence evidence) {
        jdbc.update("""
                INSERT INTO catalog.administrative_audit_events
                (id,tenant_id,actor_id,resource_id,action,before_revision,after_revision,correlation_id,occurred_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """,evidence.id(),evidence.tenantId(),evidence.actorId(),evidence.resourceId(),evidence.action(),
                evidence.beforeRevision(),evidence.afterRevision(),evidence.correlationId(),Timestamp.from(evidence.occurredAt()));
    }
}
