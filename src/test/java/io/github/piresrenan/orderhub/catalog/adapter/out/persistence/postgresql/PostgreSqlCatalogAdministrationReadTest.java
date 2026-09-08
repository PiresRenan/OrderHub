package io.github.piresrenan.orderhub.catalog.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.*;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import io.github.piresrenan.orderhub.catalog.application.port.in.administration.*;
import io.github.piresrenan.orderhub.catalog.application.service.*;
import io.github.piresrenan.orderhub.catalog.adapter.out.transaction.postgresql.PostgreSqlCatalogAdminTransactionExecutor;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;

/** Why: administration requires bounded Tenant-owned discovery.
 * Covers: PostgreSQL keyset ordering, size bounds and authorization before reads.
 * Prevents: whole-catalog responses and foreign-Tenant enumeration. */
@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class PostgreSqlCatalogAdministrationReadTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;

    /** Why: revision and aggregate hydration currently use two statements.
     * Covers: a real committed writer between those statements, or its observed PostgreSQL lock wait.
     * Prevents: returning new metadata with an obsolete revision token. */
    @Test void detailRevisionAndMetadataDescribeTheSameState() throws Exception {
        var tx=new TransactionTemplate(manager); tx.setTimeout(10);
        var actor=new CatalogAdminContext(UUID.randomUUID(),UUID.randomUUID(),"read-consistency");
        var id=UUID.randomUUID();
        var writer=new CatalogAdministrationService((a,p)->{},new PostgreSqlCatalogAdministrationRepository(jdbc,tx),
                new PostgreSqlCatalogAdminTransactionExecutor(tx),Clock.systemUTC());
        writer.createProduct(actor,id,new CatalogProductMetadata("Before","before",null,null));
        try(var workers=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var started=new java.util.concurrent.atomic.AtomicBoolean();
            var future=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
            var writerPid=new java.util.concurrent.atomic.AtomicInteger();
            var observingJdbc=new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource())) {
                @Override public <T> List<T> query(String sql,org.springframework.jdbc.core.RowMapper<T> mapper,Object... args) {
                    var result=super.query(sql,mapper,args);
                    if(sql.startsWith("SELECT revision FROM catalog.products") && started.compareAndSet(false,true)) {
                        future.set(workers.submit(()->tx.executeWithoutResult(status -> {
                            writerPid.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                            writer.updateProduct(actor,id,1,new CatalogProductMetadata("After","after",null,null));
                        })));
                        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(()->
                                future.get().isDone() || jdbc.queryForObject(
                                        "SELECT count(*) FROM pg_stat_activity WHERE pid=? AND wait_event_type='Lock'",
                                        Integer.class,writerPid.get())==1);
                    }
                    return result;
                }
            };
            var reader=new CatalogAdministrationService((a,p)->{},new PostgreSqlCatalogAdministrationRepository(observingJdbc,tx),
                    new PostgreSqlCatalogAdminTransactionExecutor(tx),Clock.systemUTC());
            var observed=reader.product(actor,id);
            future.get().get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(observed.value().name()).isEqualTo(observed.revision()==1?"Before":"After");
            assertThat(writer.product(actor,id).revision()).isEqualTo(2);
        }
    }

    @Test void pagesProductsAndVariantsWithoutCrossingTenant() {
        var tx=new TransactionTemplate(manager);
        var repo=new PostgreSqlCatalogAdministrationRepository(jdbc,tx);
        var boundary=new PostgreSqlCatalogAdminTransactionExecutor(tx);
        var products=new CatalogAdministrationService((a,p)->{},repo,boundary,Clock.systemUTC());
        var reads=new CatalogAdministrationReadService((a,p)->{},repo,boundary);
        var actor=new CatalogAdminContext(UUID.randomUUID(),UUID.randomUUID(),"read-test");
        var foreign=new CatalogAdminContext(UUID.randomUUID(),UUID.randomUUID(),"read-foreign");
        var low=UUID.fromString("00000000-0000-0000-0000-000000000001");
        var high=UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        products.createProduct(actor,low,new CatalogProductMetadata("Low","low",null,null));
        products.createProduct(actor,high,new CatalogProductMetadata("High","high",null,null));
        products.createProduct(foreign,UUID.randomUUID(),new CatalogProductMetadata("Foreign","foreign",null,null));
        products.createVariant(actor,low,low,new CatalogVariantMetadata("LOW",null,null,null,List.of()));
        products.createVariant(actor,low,high,new CatalogVariantMetadata("HIGH",null,null,null,List.of()));
        assertThat(reads.products(actor,null,1)).extracting(CatalogProductSummary::id).containsExactly(low);
        assertThat(reads.products(actor,low,1)).extracting(CatalogProductSummary::id).containsExactly(high);
        assertThat(reads.products(actor,high,1)).isEmpty();
        assertThat(reads.variants(actor,low,low,1)).extracting(CatalogVariantSummary::id).containsExactly(high);
        assertThatThrownBy(()->reads.products(actor,null,101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->reads.variants(actor,low,null,0)).isInstanceOf(IllegalArgumentException.class);
        var denied=new CatalogAdministrationReadService((a,p)->{throw new CatalogAdminDeniedException();},repo,boundary);
        assertThatThrownBy(()->denied.products(actor,null,1)).isInstanceOf(CatalogAdminDeniedException.class);
    }
}
