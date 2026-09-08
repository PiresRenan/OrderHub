package io.github.piresrenan.orderhub.catalog.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import io.github.piresrenan.orderhub.catalog.adapter.out.transaction.postgresql.PostgreSqlCatalogAdminTransactionExecutor;
import io.github.piresrenan.orderhub.catalog.application.port.in.administration.*;
import io.github.piresrenan.orderhub.catalog.application.port.in.identity.*;
import io.github.piresrenan.orderhub.catalog.application.port.out.*;
import io.github.piresrenan.orderhub.catalog.application.service.*;
import io.github.piresrenan.orderhub.catalog.domain.model.*;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;

/** Proves complete JDBC transactions, concurrent stale writers and failure rollback. */
@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class PostgreSqlCatalogAdministrationApplicationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    private PostgreSqlCatalogAdministrationRepository repository;
    private CatalogAdministrationService service;
    private PostgreSqlCatalogAdminTransactionExecutor transactions;
    private final UUID tenant=UUID.randomUUID();
    private final UUID productId=UUID.randomUUID();
    private final CatalogAdminContext actor=new CatalogAdminContext(UUID.randomUUID(),tenant,"catalog-application");

    @BeforeEach void setUp() {
        var template=new TransactionTemplate(transactionManager); template.setTimeout(5);
        transactions=new PostgreSqlCatalogAdminTransactionExecutor(template);
        repository=new PostgreSqlCatalogAdministrationRepository(jdbc,template);
        service=new CatalogAdministrationService((a,p)->{},repository,transactions,Clock.systemUTC());
    }
    @Test void productVariantLifecycleAndMetadataKeepIdentityAndRevision() {
        service.createProduct(actor,productId,metadata("Product"));
        var variantId=UUID.randomUUID();
        service.createVariant(actor,productId,variantId,variantMetadata("sku"));
        assertThat(service.activateVariant(actor,variantId,1).value().status()).isEqualTo(ProductVariantStatus.ACTIVE);
        assertThat(service.activateProduct(actor,productId,1).value().status()).isEqualTo(ProductStatus.ACTIVE);
        var variant=service.updateVariant(actor,variantId,2,variantMetadata("new-sku"));
        assertThat(variant.value().productId()).isEqualTo(productId);
        assertThat(variant.value().status()).isEqualTo(ProductVariantStatus.ACTIVE);
        assertThat(variant.revision()).isEqualTo(3);
        service.deactivateVariant(actor,variantId,3);
        service.archiveVariant(actor,variantId,4);
        assertThatThrownBy(()->service.activateVariant(actor,variantId,5)).isInstanceOf(CatalogAdminConflictException.class);
        service.archiveProduct(actor,productId,2);
        assertThat(service.product(actor,productId).value().status()).isEqualTo(ProductStatus.ARCHIVED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM catalog.administrative_audit_events WHERE tenant_id=?",Long.class,tenant)).isEqualTo(8);
    }
    @Test void twoConcurrentMetadataWritersCannotBothCommitTheSameRevision() throws Exception {
        service.createProduct(actor,productId,metadata("Product"));
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->attempt(start,()->service.updateProduct(actor,productId,1,metadata("First"))));
            var second=pool.submit(()->attempt(start,()->service.updateProduct(actor,productId,1,metadata("Second"))));
            start.countDown();
            assertThat(List.of(first.get(8,TimeUnit.SECONDS),second.get(8,TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("applied","conflict");
        }
        assertThat(service.product(actor,productId).revision()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM catalog.administrative_audit_events WHERE tenant_id=?",Long.class,tenant)).isEqualTo(2);
    }
    @Test void evidenceDatabaseFailureRollsBackAuthoritativeInsert() {
        jdbc.execute("""
            CREATE OR REPLACE FUNCTION catalog.test_reject_admin_evidence() RETURNS TRIGGER LANGUAGE plpgsql AS $$
            BEGIN IF NEW.correlation_id='fail-audit' THEN RAISE EXCEPTION 'synthetic evidence failure'; END IF; RETURN NEW; END; $$
            """);
        jdbc.execute("CREATE TRIGGER test_reject_admin_evidence BEFORE INSERT ON catalog.administrative_audit_events FOR EACH ROW EXECUTE FUNCTION catalog.test_reject_admin_evidence()");
        try {
            var failing=new CatalogAdminContext(actor.userId(),tenant,"fail-audit");
            assertThatThrownBy(()->service.createProduct(failing,productId,metadata("Rollback")))
                .isInstanceOf(CatalogAdminUnavailableException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM catalog.products WHERE tenant_id=? AND id=?",Long.class,tenant,productId)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM catalog.administrative_audit_events WHERE tenant_id=?",Long.class,tenant)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER test_reject_admin_evidence ON catalog.administrative_audit_events");
            jdbc.execute("DROP FUNCTION catalog.test_reject_admin_evidence()");
        }
    }
    @Test void identityValidationAcceptsEveryLifecycleButRejectsForeignTenant() {
        service.createProduct(actor,productId,metadata("Product"));
        var variantId=UUID.randomUUID();
        service.createVariant(actor,productId,variantId,variantMetadata("identity-sku"));
        var validator=new PostgreSqlValidateVariantIdentity(jdbc);
        for(var status:ProductVariantStatus.values()) {
            jdbc.update("UPDATE catalog.product_variants SET status=? WHERE tenant_id=? AND id=?",status.name(),tenant,variantId);
            transactions.execute(()->{validator.validate(tenant,variantId);return null;});
        }
        assertThatThrownBy(()->transactions.execute(()->{validator.validate(UUID.randomUUID(),variantId);return null;}))
            .isInstanceOf(CatalogVariantIdentityRejectedException.class);
        assertThatThrownBy(()->validator.validate(tenant,variantId)).isInstanceOf(CatalogVariantIdentityUnavailableException.class);
    }
    @Test void duplicateIdentityCannotRepurposeProductAndForeignParentCannotCreateVariant() {
        service.createProduct(actor,productId,metadata("Original"));
        assertThatThrownBy(()->service.createProduct(actor,productId,metadata("Replacement")))
            .isInstanceOf(CatalogAdminConflictException.class);
        var foreign=new CatalogAdminContext(actor.userId(),UUID.randomUUID(),"foreign");
        assertThatThrownBy(()->service.createVariant(foreign,productId,UUID.randomUUID(),variantMetadata("foreign-sku")))
            .isInstanceOf(CatalogAdminNotFoundException.class);
        assertThat(service.product(actor,productId).value().name()).isEqualTo("Original");
    }
    /** Why: merchant uniqueness is a business conflict. Covers: stale-free duplicate SKU mutation.
     * Prevents: a valid conflicting command being reported as an internal error or partly audited. */
    @Test void duplicateSkuMetadataIsAConflictAndRollsBackItsRevision() {
        service.createProduct(actor,productId,metadata("Original"));
        var first=UUID.randomUUID(); var second=UUID.randomUUID();
        service.createVariant(actor,productId,first,variantMetadata("first"));
        service.createVariant(actor,productId,second,variantMetadata("second"));
        assertThatThrownBy(()->service.updateVariant(actor,second,1,variantMetadata("first")))
                .isInstanceOf(CatalogAdminConflictException.class);
        assertThat(service.variant(actor,second).revision()).isEqualTo(1);
        assertThat(service.variant(actor,second).value().sku()).isEqualTo("second");
    }
    private String attempt(CountDownLatch start,Supplier<?> action) throws Exception {
        start.await(3,TimeUnit.SECONDS);
        try {action.get();return "applied";} catch(CatalogAdminConflictException e) {return "conflict";}
    }
    private CatalogProductMetadata metadata(String name) {return new CatalogProductMetadata(name,"product",null,null);}
    private CatalogVariantMetadata variantMetadata(String sku) {return new CatalogVariantMetadata(sku,null,null,null,List.of());}
}
