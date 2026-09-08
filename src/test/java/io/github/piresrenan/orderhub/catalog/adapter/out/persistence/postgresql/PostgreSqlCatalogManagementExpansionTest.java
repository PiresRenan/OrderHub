package io.github.piresrenan.orderhub.catalog.adapter.out.persistence.postgresql;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Clock;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import io.github.piresrenan.orderhub.catalog.application.port.out.*;
import io.github.piresrenan.orderhub.catalog.application.port.in.*;
import io.github.piresrenan.orderhub.catalog.application.port.in.administration.*;
import io.github.piresrenan.orderhub.catalog.application.service.*;
import io.github.piresrenan.orderhub.catalog.adapter.out.transaction.postgresql.PostgreSqlCatalogAdminTransactionExecutor;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;

/** Why: Category and price writes need durable preconditions and evidence.
 * Covers: actual PostgreSQL schema support rather than optimistic fields in Java only.
 * Prevents: authoring APIs whose stale-write protection disappears across processes. */
@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class PostgreSqlCatalogManagementExpansionTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired CategoryHierarchyMutationExecutor guard;
    @Autowired SaveCategoryUseCase save;
    /** Why: evidence must participate in the authoritative transaction.
     * Covers: a real PostgreSQL evidence constraint failure after Category and price mutation.
     * Prevents: successful state changes without accountability facts. */
    @Test void evidenceFailureRollsBackCategoryAndPriceWrites() {
        var tx=new TransactionTemplate(manager); var repo=new PostgreSqlCatalogAdministrationRepository(jdbc,tx);
        var boundary=new PostgreSqlCatalogAdminTransactionExecutor(tx);
        var categories=new CatalogCategoryAdministrationService((a,p)->{},repo,boundary,guard,save,Clock.systemUTC());
        var products=new CatalogAdministrationService((a,p)->{},repo,boundary,Clock.systemUTC());
        var prices=new CatalogPricingAdministrationService((a,p)->{},repo,boundary,Clock.systemUTC());
        var actor=new CatalogAdminContext(UUID.randomUUID(),UUID.randomUUID(),"evidence-failure");
        var category=UUID.randomUUID(); var product=UUID.randomUUID(); var variant=UUID.randomUUID();
        categories.create(actor,category,null,"Before","before",null);
        products.createProduct(actor,product,new CatalogProductMetadata("Product","product",null,null));
        products.createVariant(actor,product,variant,new CatalogVariantMetadata("SKU",null,null,null,List.of()));
        // Only generated UUID text enters this fixture-only DDL; existing evidence remains valid.
        jdbc.execute("ALTER TABLE catalog.administrative_audit_events ADD CONSTRAINT test_reject_expansion_evidence CHECK (tenant_id<>'"
                +actor.tenantId()+"'::uuid) NOT VALID");
        try {
            assertThatThrownBy(()->categories.metadata(actor,category,1,"After","after",null))
                    .isInstanceOf(CatalogAdminUnavailableException.class);
            assertThatThrownBy(()->prices.set(actor,variant,"BRL",0,100))
                    .isInstanceOf(CatalogAdminUnavailableException.class);
            assertThat(categories.get(actor,category).revision()).isEqualTo(1);
            assertThat(categories.get(actor,category).value().name()).isEqualTo("Before");
            assertThatThrownBy(()->prices.get(actor,variant,"BRL")).isInstanceOf(CatalogAdminNotFoundException.class);
        } finally { jdbc.execute("ALTER TABLE catalog.administrative_audit_events DROP CONSTRAINT test_reject_expansion_evidence"); }
    }
    /** Why: merchant slug collisions are business conflicts, not technical outages.
     * Covers: the existing Category repository exception boundary.
     * Prevents: reporting a retryable server failure for a permanently conflicting command. */
    @Test void duplicateCategorySlugIsAConflictAndLeavesNoAudit() {
        var tx=new TransactionTemplate(manager);
        var categories=new CatalogCategoryAdministrationService((a,p)->{},new PostgreSqlCatalogAdministrationRepository(jdbc,tx),
                new PostgreSqlCatalogAdminTransactionExecutor(tx),guard,save,Clock.systemUTC());
        var actor=new CatalogAdminContext(UUID.randomUUID(),UUID.randomUUID(),"duplicate-category");
        categories.create(actor,UUID.randomUUID(),null,"One","same",null);
        assertThatThrownBy(()->categories.create(actor,UUID.randomUUID(),null,"Two","same",null))
                .isInstanceOf(CatalogAdminConflictException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog.administrative_audit_events WHERE tenant_id=?",
                Integer.class,actor.tenantId())).isEqualTo(1);
    }
    /** Why: row triggers do not intercept TRUNCATE. Covers: actual statement execution.
     * Prevents: privileged DML consumers erasing the accountability trail. */
    @Test void rejectsAuditTruncateEvenOnAnEmptyTable() {
        var tx = new TransactionTemplate(manager);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            status.setRollbackOnly();
            jdbc.execute("TRUNCATE catalog.administrative_audit_events");
        })).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void persistsCategoryAndPriceRevisionPreconditions() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns WHERE table_schema='catalog'
                AND table_name IN ('categories','variant_base_prices') AND column_name='revision'
                """,Integer.class)).isEqualTo(2);
    }
    @Test void categoryReparentingPreservesTreeAndRejectsStaleMetadata() {
        var tx=new TransactionTemplate(manager); tx.setTimeout(5);
        var repo=new PostgreSqlCatalogAdministrationRepository(jdbc,tx);
        var categories=new CatalogCategoryAdministrationService((a,p)->{},repo,
                new PostgreSqlCatalogAdminTransactionExecutor(tx),guard,save,Clock.systemUTC());
        var actor=new CatalogAdminContext(UUID.randomUUID(),UUID.randomUUID(),"category-expansion");
        var a=UUID.randomUUID(); var b=UUID.randomUUID();
        categories.create(actor,a,null,"A","aa",null); categories.create(actor,b,null,"B","bb",null);
        assertThat(categories.reparent(actor,a,1,b).revision()).isEqualTo(2);
        assertThatThrownBy(()->categories.reparent(actor,b,1,a)).isInstanceOf(CategoryHierarchyViolationException.class);
        assertThatThrownBy(()->categories.reparent(actor,a,1,null)).isInstanceOf(CatalogAdminConflictException.class);
        assertThat(jdbc.queryForObject("SELECT parent_category_id FROM catalog.categories WHERE tenant_id=? AND id=?",
                UUID.class,actor.tenantId(),a)).isEqualTo(b);
    }
    @Test void exactPriceHasARevisionAndAtomicFinancialEvidence() {
        var tx=new TransactionTemplate(manager); tx.setTimeout(5);
        var repo=new PostgreSqlCatalogAdministrationRepository(jdbc,tx);
        var boundary=new PostgreSqlCatalogAdminTransactionExecutor(tx);
        var products=new CatalogAdministrationService((a,p)->{},repo,boundary,Clock.systemUTC());
        var prices=new CatalogPricingAdministrationService((a,p)->{},repo,boundary,Clock.systemUTC());
        var actor=new CatalogAdminContext(UUID.randomUUID(),UUID.randomUUID(),"price-expansion");
        var product=UUID.randomUUID(); var variant=UUID.randomUUID();
        products.createProduct(actor,product,new CatalogProductMetadata("P","pp",null,null));
        products.createVariant(actor,product,variant,new CatalogVariantMetadata("SKU",null,null,null,List.of()));
        long exact=9007199254740993L;
        assertThat(prices.set(actor,variant,"BRL",0,exact).value().minorUnits()).isEqualTo(exact);
        assertThat(prices.set(actor,variant,"BRL",1,exact+1).revision()).isEqualTo(2);
        assertThatThrownBy(()->prices.set(actor,variant,"BRL",1,1)).isInstanceOf(CatalogAdminConflictException.class);
        assertThat(jdbc.queryForObject("SELECT before_minor_units FROM catalog.administrative_audit_events WHERE tenant_id=? AND action='BASE_PRICE_SET' AND before_revision=1",
                Long.class,actor.tenantId())).isEqualTo(exact);
    }
    /** Why: assignment replacement is a noncommutative Tenant-owned write.
     * Covers: exact assignments, stale preconditions and foreign Category rejection.
     * Prevents: cross-Tenant classification and silent overwrite of current metadata. */
    @Test void assignmentsRequireCurrentRevisionAndOwnedCategories() {
        var tx=new TransactionTemplate(manager); tx.setTimeout(5);
        var repo=new PostgreSqlCatalogAdministrationRepository(jdbc,tx);
        var boundary=new PostgreSqlCatalogAdminTransactionExecutor(tx);
        var products=new CatalogAdministrationService((a,p)->{},repo,boundary,Clock.systemUTC());
        var categories=new CatalogCategoryAdministrationService((a,p)->{},repo,boundary,guard,save,Clock.systemUTC());
        var actor=new CatalogAdminContext(UUID.randomUUID(),UUID.randomUUID(),"assignment-test");
        var foreign=new CatalogAdminContext(UUID.randomUUID(),UUID.randomUUID(),"foreign-test");
        var product=UUID.randomUUID(); var own=UUID.randomUUID(); var other=UUID.randomUUID();
        products.createProduct(actor,product,new CatalogProductMetadata("P","pp",null,null));
        categories.create(actor,own,null,"Owned","owned",null);
        categories.create(foreign,other,null,"Foreign","foreign",null);
        assertThat(products.assignCategories(actor,product,1,List.of(own)).value().categoryIds()).containsExactly(own);
        assertThatThrownBy(()->products.assignCategories(actor,product,1,List.of()))
                .isInstanceOf(CatalogAdminConflictException.class);
        assertThatThrownBy(()->products.assignCategories(actor,product,2,List.of(other)))
                .isInstanceOf(CatalogAdminNotFoundException.class);
        assertThat(products.product(actor,product).revision()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog.administrative_audit_events WHERE tenant_id=? AND action='PRODUCT_CATEGORIES_CHANGED'",
                Integer.class,actor.tenantId())).isEqualTo(1);
    }
}
