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
        return jdbc.query("SELECT revision FROM "+table+" WHERE tenant_id=? AND id=?"+(lock?" FOR UPDATE":" FOR SHARE"),
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
    /** One index-bounded root query; lists do not load each Product's assignments. */
    @Override public java.util.List<CatalogProductSummary> products(UUID tenant,UUID afterId,int limit) {
        return jdbc.query("SELECT id,name,slug,status,revision FROM catalog.products WHERE tenant_id=?"
                +(afterId==null?"":" AND id>?")+" ORDER BY id LIMIT ?",
                (row,n)->new CatalogProductSummary(row.getObject("id",UUID.class),row.getString("name"),row.getString("slug"),
                        ProductStatus.valueOf(row.getString("status")),row.getLong("revision")),
                afterId==null?new Object[]{tenant,limit}:new Object[]{tenant,afterId,limit});
    }
    /** One bounded projection avoids per-Variant attribute queries. */
    @Override public java.util.List<CatalogVariantSummary> variants(UUID tenant,UUID product,UUID afterId,int limit) {
        return jdbc.query("SELECT id,product_id,sku,display_name,status,revision FROM catalog.product_variants WHERE tenant_id=? AND product_id=?"
                +(afterId==null?"":" AND id>?")+" ORDER BY id LIMIT ?",
                (row,n)->new CatalogVariantSummary(row.getObject("id",UUID.class),row.getObject("product_id",UUID.class),row.getString("sku"),
                        row.getString("display_name"),ProductVariantStatus.valueOf(row.getString("status")),row.getLong("revision")),
                afterId==null?new Object[]{tenant,product,limit}:new Object[]{tenant,product,afterId,limit});
    }
    /** Each Category and revision come from one statement snapshot. */
    @Override public java.util.List<CatalogRevision<Category>> categories(UUID tenant,UUID afterId,int limit) {
        return jdbc.query("SELECT id,parent_category_id,name,slug,description,revision FROM catalog.categories WHERE tenant_id=?"
                +(afterId==null?"":" AND id>?")+" ORDER BY id LIMIT ?",
                (row,n)->new CatalogRevision<>(Category.create(row.getObject("id",UUID.class),tenant,row.getObject("parent_category_id",UUID.class),
                        row.getString("name"),row.getString("slug"),row.getString("description")),row.getLong("revision")),
                afterId==null?new Object[]{tenant,limit}:new Object[]{tenant,afterId,limit});
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
                (id,tenant_id,actor_id,resource_id,action,before_revision,after_revision,correlation_id,occurred_at,
                    currency_code,before_minor_units,after_minor_units)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """,evidence.id(),evidence.tenantId(),evidence.actorId(),evidence.resourceId(),evidence.action(),
                evidence.beforeRevision(),evidence.afterRevision(),evidence.correlationId(),Timestamp.from(evidence.occurredAt()),
                evidence.currencyCode(),evidence.beforeMinorUnits(),evidence.afterMinorUnits());
    }
    /** Reads the Category and revision in one snapshot without locking ahead of the hierarchy guard. */
    @Override public Optional<CatalogRevision<Category>> category(UUID tenant,UUID id) {
        return jdbc.query("SELECT parent_category_id,name,slug,description,revision FROM catalog.categories WHERE tenant_id=? AND id=?",
                (row,n)->new CatalogRevision<>(Category.create(id,tenant,row.getObject("parent_category_id",UUID.class),
                        row.getString("name"),row.getString("slug"),row.getString("description")),row.getLong("revision")),tenant,id)
                .stream().findFirst();
    }
    /** Enforces the expected Category revision inside the existing hierarchy transaction. */
    @Override public void advanceCategory(UUID tenant,UUID id,long expected) { advance("catalog.categories",tenant,id,expected); }
    /** Reads exact money and its local revision as one PostgreSQL snapshot. */
    @Override public Optional<CatalogRevision<VariantBasePrice>> price(UUID tenant,UUID variant,String currency,boolean lock) {
        return jdbc.query("SELECT minor_units,revision FROM catalog.variant_base_prices WHERE tenant_id=? AND variant_id=? AND currency_code=?"
                +(lock?" FOR UPDATE":""),(row,n)->new CatalogRevision<>(VariantBasePrice.create(tenant,variant,
                        Money.of(currency,row.getLong("minor_units"))),row.getLong("revision")),tenant,variant,currency).stream().findFirst();
    }
    /** Unique acquisition and conditional revisions arbitrate concurrent base-price writers. */
    @Override public void savePrice(VariantBasePrice price,long expected) {
        int changed;
        if(expected==0) {
            changed=jdbc.update("""
                    INSERT INTO catalog.variant_base_prices(tenant_id,variant_id,currency_code,minor_units)
                    VALUES (?,?,?,?) ON CONFLICT (tenant_id,variant_id,currency_code) DO NOTHING
                    """,price.tenantId(),price.variantId(),price.currencyCode(),price.minorUnits());
        } else {
            changed=jdbc.update("""
                    UPDATE catalog.variant_base_prices SET minor_units=?,revision=revision+1
                    WHERE tenant_id=? AND variant_id=? AND currency_code=? AND revision=? AND revision<9223372036854775807
                    """,price.minorUnits(),price.tenantId(),price.variantId(),price.currencyCode(),expected);
        }
        if(changed!=1) throw new CatalogAdminConflictException();
    }
}
