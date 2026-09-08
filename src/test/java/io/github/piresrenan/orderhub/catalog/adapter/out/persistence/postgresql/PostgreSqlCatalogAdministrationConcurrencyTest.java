package io.github.piresrenan.orderhub.catalog.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.*;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import io.github.piresrenan.orderhub.catalog.application.port.in.*;
import io.github.piresrenan.orderhub.catalog.application.port.in.administration.*;
import io.github.piresrenan.orderhub.catalog.application.port.out.CategoryHierarchyMutationExecutor;
import io.github.piresrenan.orderhub.catalog.application.service.*;
import io.github.piresrenan.orderhub.catalog.adapter.out.transaction.postgresql.PostgreSqlCatalogAdminTransactionExecutor;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;

/** Why: tree and price correctness depend on PostgreSQL arbitration across processes.
 * Covers: observed lock waits with independently committed transactions.
 * Prevents: tests that merely schedule two operations without proving overlap. */
@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class PostgreSqlCatalogAdministrationConcurrencyTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired CategoryHierarchyMutationExecutor guard;
    @Autowired SaveCategoryUseCase save;

    @Test void opposingReparentsSerializeAndUnrelatedTenantRemainsIndependent() throws Exception {
        var tx=transaction();
        var repo=new PostgreSqlCatalogAdministrationRepository(jdbc,tx);
        var categories=new CatalogCategoryAdministrationService((a,p)->{},repo,
                new PostgreSqlCatalogAdminTransactionExecutor(tx),guard,save,Clock.systemUTC());
        var actor=actor(); var other=actor(); var a=UUID.randomUUID(); var b=UUID.randomUUID();
        categories.create(actor,a,null,"A","aa",null); categories.create(actor,b,null,"B","bb",null);
        var held=new CountDownLatch(1); var release=new CountDownLatch(1); var contenderPid=new AtomicInteger();
        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(3)) {
            var first=workers.submit(()->tx.executeWithoutResult(status->{
                categories.reparent(actor,a,1,b); held.countDown(); await(release);
            }));
            try {
                assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();
                var second=workers.submit(()->catchThrowable(()->tx.executeWithoutResult(status->{
                    contenderPid.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                    categories.reparent(actor,b,1,a);
                })));
                awaitLock(contenderPid);
                var independent=workers.submit(()->categories.create(other,UUID.randomUUID(),null,"Independent","independent",null));
                assertThat(independent.get(5,TimeUnit.SECONDS).revision()).isEqualTo(1);
                release.countDown(); first.get(5,TimeUnit.SECONDS);
                assertThat(second.get(5,TimeUnit.SECONDS)).isInstanceOf(CategoryHierarchyViolationException.class);
                assertThat(categories.get(actor,a).value().parentCategoryId()).isEqualTo(b);
                assertThat(categories.get(actor,b).value().parentCategoryId()).isNull();
            } finally { release.countDown(); }
        }
    }

    @Test void competingFirstPricesHaveOneWinnerAndOneEvidenceFact() throws Exception {
        var tx=transaction(); var repo=new PostgreSqlCatalogAdministrationRepository(jdbc,tx);
        var boundary=new PostgreSqlCatalogAdminTransactionExecutor(tx);
        var products=new CatalogAdministrationService((a,p)->{},repo,boundary,Clock.systemUTC());
        var prices=new CatalogPricingAdministrationService((a,p)->{},repo,boundary,Clock.systemUTC());
        var actor=actor(); var product=UUID.randomUUID(); var variant=UUID.randomUUID();
        products.createProduct(actor,product,new CatalogProductMetadata("Product","product",null,null));
        products.createVariant(actor,product,variant,new CatalogVariantMetadata("SKU",null,null,null,List.of()));
        var held=new CountDownLatch(1); var release=new CountDownLatch(1); var contenderPid=new AtomicInteger();
        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first=workers.submit(()->tx.executeWithoutResult(status->{
                prices.set(actor,variant,"BRL",0,100); held.countDown(); await(release);
            }));
            try {
                assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();
                var second=workers.submit(()->catchThrowable(()->tx.executeWithoutResult(status->{
                    contenderPid.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                    prices.set(actor,variant,"BRL",0,200);
                })));
                awaitLock(contenderPid); release.countDown(); first.get(5,TimeUnit.SECONDS);
                assertThat(second.get(5,TimeUnit.SECONDS)).isInstanceOf(CatalogAdminConflictException.class);
                assertThat(prices.get(actor,variant,"BRL").value().minorUnits()).isEqualTo(100);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog.administrative_audit_events WHERE tenant_id=? AND action='BASE_PRICE_SET'",
                        Integer.class,actor.tenantId())).isEqualTo(1);
            } finally { release.countDown(); }
        }
    }
    private TransactionTemplate transaction() { var tx=new TransactionTemplate(manager); tx.setTimeout(15); return tx; }
    private CatalogAdminContext actor() { return new CatalogAdminContext(UUID.randomUUID(),UUID.randomUUID(),"concurrency-test"); }
    private void awaitLock(AtomicInteger pid) {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(()->jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE pid=? AND wait_event_type='Lock'",Integer.class,pid.get())==1);
    }
    private static void await(CountDownLatch latch) {
        try { if(!latch.await(10,TimeUnit.SECONDS)) throw new AssertionError("Test transaction was not released"); }
        catch(InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
    }
}
