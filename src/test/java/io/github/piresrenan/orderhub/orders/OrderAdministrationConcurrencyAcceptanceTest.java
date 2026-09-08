package io.github.piresrenan.orderhub.orders;

import static org.assertj.core.api.Assertions.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import io.github.piresrenan.orderhub.catalog.adapter.out.persistence.postgresql.*;
import io.github.piresrenan.orderhub.catalog.adapter.out.transaction.postgresql.PostgreSqlCatalogAdminTransactionExecutor;
import io.github.piresrenan.orderhub.catalog.application.port.in.administration.CatalogAdminContext;
import io.github.piresrenan.orderhub.catalog.application.service.CatalogAdministrationService;
import io.github.piresrenan.orderhub.inventory.adapter.out.persistence.postgresql.*;
import io.github.piresrenan.orderhub.inventory.adapter.out.transaction.spring.SpringInventoryAdministrationTransactionExecutor;
import io.github.piresrenan.orderhub.inventory.application.port.in.*;
import io.github.piresrenan.orderhub.inventory.application.service.*;
import io.github.piresrenan.orderhub.inventory.domain.model.*;
import io.github.piresrenan.orderhub.orders.application.port.in.*;
import io.github.piresrenan.orderhub.orders.support.TestCreateOrderIdempotencyKeyDigests;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;

/** Why: OH-018 mutations must remain correct against the actual atomic Order workflow.
 * Covers: independently committed transactions and observed PostgreSQL lock waits in both directions.
 * Prevents: administration invalidating Catalog eligibility or corrupting accepted commitments.
 * Authentication is isolated here; real JWT and Staff composition have separate HTTP acceptance. */
@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class OrderAdministrationConcurrencyAcceptanceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired CreateOrderUseCase orders;
    UUID tenant,actor,product,variant;
    TransactionTemplate tx;
    RecordInventoryMovementService movements;
    InventoryPolicyAdministrationService policies;
    CatalogAdministrationService catalog;

    @BeforeEach void seedOwnedActiveResources() {
        tenant=UUID.randomUUID(); actor=UUID.randomUUID(); product=UUID.randomUUID(); variant=UUID.randomUUID();
        tx=new TransactionTemplate(manager); tx.setTimeout(15);
        jdbc.update("INSERT INTO catalog.products(tenant_id,id,name,slug,status) VALUES (?,?,'Product','product','ACTIVE')",tenant,product);
        jdbc.update("INSERT INTO catalog.product_variants(tenant_id,id,product_id,sku,status) VALUES (?,?,?,'SKU','ACTIVE')",tenant,variant,product);
        jdbc.update("INSERT INTO inventory.tenant_policies(tenant_id,policy) VALUES (?,'DENY')",tenant);
        jdbc.update("INSERT INTO inventory.inventory_positions(tenant_id,variant_id,on_hand,committed,backordered,safety_stock) VALUES (?,?,10,0,0,0)",tenant,variant);
        movements=movements(tx);
        policies=new InventoryPolicyAdministrationService((a,t,p)->{},new SpringInventoryAdministrationTransactionExecutor(tx),
                new PostgreSqlInventoryPolicyAdministrationRepository(jdbc),Instant::now);
        catalog=new CatalogAdministrationService((a,p)->{},new PostgreSqlCatalogAdministrationRepository(jdbc,tx),
                new PostgreSqlCatalogAdminTransactionExecutor(tx),Clock.systemUTC());
    }

    @ParameterizedTest @EnumSource(InventoryChange.class)
    void administrationWinningFirstSerializesConflictingOrderMutation(InventoryChange change) throws Exception {
        var held=new CountDownLatch(1); var release=new CountDownLatch(1); var pid=new AtomicInteger();
        try(var workers=Executors.newFixedThreadPool(2)) {
            var administration=workers.submit(()->tx.executeWithoutResult(status->{change(change); held.countDown(); await(release);}));
            try {
                assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();
                int quantity=change==InventoryChange.POLICY ? 15 : 8;
                var order=workers.submit(()->catchThrowable(()->tx.executeWithoutResult(status->{
                    pid.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class)); orders.create(command(quantity));
                })));
                awaitLock(pid); release.countDown(); administration.get(5,TimeUnit.SECONDS);
                var failure=order.get(5,TimeUnit.SECONDS);
                if(change==InventoryChange.RECEIPT || change==InventoryChange.POLICY) assertThat(failure).isNull();
                else assertThat(failure).isNotNull();
                assertThat(count("orders.orders")).isEqualTo(failure==null?1:0);
                assertThat(quantity("on_hand")).isEqualTo(change==InventoryChange.RECEIPT?20:change==InventoryChange.ADJUSTMENT?4:10);
                assertThat(quantity("committed")).isEqualTo(change==InventoryChange.RECEIPT?8:change==InventoryChange.POLICY?10:0);
                assertThat(quantity("backordered")).isEqualTo(change==InventoryChange.POLICY?5:0);
            } finally { release.countDown(); }
        }
    }

    @ParameterizedTest @EnumSource(InventoryChange.class)
    void acceptedOrderWinningFirstRetainsItsCommitment(InventoryChange change) throws Exception {
        var held=new CountDownLatch(1); var release=new CountDownLatch(1); var pid=new AtomicInteger();
        try(var workers=Executors.newFixedThreadPool(2)) {
            var order=workers.submit(()->tx.executeWithoutResult(status->{orders.create(command(8)); held.countDown(); await(release);}));
            try {
                assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();
                var administration=workers.submit(()->catchThrowable(()->tx.executeWithoutResult(status->{
                    pid.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class)); change(change);
                })));
                awaitLock(pid); release.countDown(); order.get(5,TimeUnit.SECONDS);
                var failure=administration.get(5,TimeUnit.SECONDS);
                if(change==InventoryChange.ADJUSTMENT) {
                    assertThat(failure).isInstanceOf(InventoryAdministrationException.class);
                    assertThat(((InventoryAdministrationException)failure).reason()).isEqualTo(InventoryAdministrationException.Reason.QUANTITY_CONFLICT);
                    assertThat(count("inventory.movements")).isZero();
                } else assertThat(failure).isNull();
                assertThat(count("orders.orders")).isEqualTo(1);
                assertThat(quantity("committed")).isEqualTo(8);
                assertThat(quantity("backordered")).isZero();
                assertThat(quantity("on_hand")).isEqualTo(change==InventoryChange.RECEIPT?20:10);
            } finally { release.countDown(); }
        }
    }

    @ParameterizedTest @EnumSource(CatalogChange.class)
    void catalogAdministrationWinningFirstRejectsWaitingOrder(CatalogChange change) throws Exception {
        var held=new CountDownLatch(1); var release=new CountDownLatch(1); var pid=new AtomicInteger();
        try(var workers=Executors.newFixedThreadPool(2)) {
            var administration=workers.submit(()->tx.executeWithoutResult(status->{retire(change); held.countDown(); await(release);}));
            try {
                assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();
                var order=workers.submit(()->catchThrowable(()->tx.executeWithoutResult(status->{
                    pid.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class)); orders.create(command(1));
                })));
                awaitLock(pid); release.countDown(); administration.get(5,TimeUnit.SECONDS);
                assertThat(order.get(5,TimeUnit.SECONDS)).isNotNull();
                assertThat(count("orders.orders")).isZero(); assertThat(quantity("committed")).isZero();
            } finally { release.countDown(); }
        }
    }

    @ParameterizedTest @EnumSource(CatalogChange.class)
    void catalogAdministrationWaitsForAcceptedOrderSnapshot(CatalogChange change) throws Exception {
        var held=new CountDownLatch(1); var release=new CountDownLatch(1); var pid=new AtomicInteger();
        try(var workers=Executors.newFixedThreadPool(2)) {
            var order=workers.submit(()->tx.executeWithoutResult(status->{orders.create(command(1)); held.countDown(); await(release);}));
            try {
                assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();
                var administration=workers.submit(()->tx.executeWithoutResult(status->{
                    pid.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class)); retire(change);
                }));
                awaitLock(pid); release.countDown(); order.get(5,TimeUnit.SECONDS); administration.get(5,TimeUnit.SECONDS);
                assertThat(count("orders.orders")).isEqualTo(1); assertThat(quantity("committed")).isEqualTo(1);
                assertThat(count("catalog.administrative_audit_events")).isEqualTo(1);
            } finally { release.countDown(); }
        }
    }

    @Test void concurrentNegativeAdjustmentsCannotSpendTheSamePhysicalStock() throws Exception {
        var held=new CountDownLatch(1); var release=new CountDownLatch(1); var pid=new AtomicInteger();
        try(var workers=Executors.newFixedThreadPool(2)) {
            var first=workers.submit(()->tx.executeWithoutResult(status->{movements.record(movement(InventoryMovementType.ADJUSTMENT,-7)); held.countDown(); await(release);}));
            try {
                assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();
                var second=workers.submit(()->catchThrowable(()->tx.executeWithoutResult(status->{
                    pid.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class)); movements.record(movement(InventoryMovementType.ADJUSTMENT,-7));
                })));
                awaitLock(pid); release.countDown(); first.get(5,TimeUnit.SECONDS);
                assertThat(second.get(5,TimeUnit.SECONDS)).isInstanceOf(InventoryAdministrationException.class);
                assertThat(quantity("on_hand")).isEqualTo(3); assertThat(count("inventory.movements")).isEqualTo(1);
            } finally { release.countDown(); }
        }
    }

    /** A receipt is not available until committed; a false UPDATE predicate need not wait on its row. */
    @Test void uncommittedReceiptMayBeRejectedBeforeItsStockBecomesVisible() throws Exception {
        var held=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var receipt=workers.submit(()->tx.executeWithoutResult(status->{
                movements.record(movement(InventoryMovementType.RECEIPT,10)); held.countDown(); await(release);
            }));
            try {
                assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();
                var rejected=workers.submit(()->catchThrowable(()->orders.create(command(15))));
                assertThat(rejected.get(5,TimeUnit.SECONDS)).isInstanceOf(InventoryCommitmentRejectedException.class);
                assertThat(count("orders.orders")).isZero();
                release.countDown(); receipt.get(5,TimeUnit.SECONDS);
                orders.create(command(15));
                assertThat(quantity("on_hand")).isEqualTo(20); assertThat(quantity("committed")).isEqualTo(15);
            } finally { release.countDown(); }
        }
    }

    @Test void lockTimeoutRollsBackMovementAcquisitionWithoutChangingStock() throws Exception {
        var held=new CountDownLatch(1); var release=new CountDownLatch(1);
        var shortTx=new TransactionTemplate(manager); shortTx.setTimeout(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var blocker=workers.submit(()->tx.executeWithoutResult(status->{
                jdbc.queryForObject("SELECT variant_id FROM inventory.inventory_positions WHERE tenant_id=? AND variant_id=? FOR UPDATE",UUID.class,tenant,variant);
                held.countDown(); await(release);
            }));
            try {
                assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();
                var result=workers.submit(()->catchThrowable(()->movements(shortTx).record(movement(InventoryMovementType.RECEIPT,1))));
                var failure=result.get(5,TimeUnit.SECONDS);
                assertThat(failure).isInstanceOf(InventoryAdministrationException.class);
                assertThat(((InventoryAdministrationException)failure).reason()).isEqualTo(InventoryAdministrationException.Reason.TECHNICAL);
                assertThat(count("inventory.movements")).isZero(); assertThat(quantity("on_hand")).isEqualTo(10);
            } finally { release.countDown(); blocker.get(5,TimeUnit.SECONDS); }
        }
    }

    private RecordInventoryMovementService movements(TransactionTemplate template) {
        var identity=new PostgreSqlValidateVariantIdentity(jdbc);
        return new RecordInventoryMovementService((a,t,p)->{},new SpringInventoryAdministrationTransactionExecutor(template),identity::validate,
                new PostgreSqlInventoryMovementRepository(jdbc),Instant::now);
    }
    private void change(InventoryChange change) {
        switch(change) {
            case RECEIPT -> movements.record(movement(InventoryMovementType.RECEIPT,10));
            case ADJUSTMENT -> movements.record(movement(InventoryMovementType.ADJUSTMENT,-6));
            case SAFETY -> policies.safetyStock(actor,tenant,variant,0,8,"SAFETY_CHANGE",UUID.randomUUID());
            case POLICY -> policies.policy(actor,tenant,InventoryPolicy.DENY,InventoryPolicy.ALLOW_BACKORDER,"POLICY_CHANGE",UUID.randomUUID());
        }
    }
    private void retire(CatalogChange change) {
        var context=new CatalogAdminContext(actor,tenant,"order-race");
        if(change==CatalogChange.PRODUCT) catalog.archiveProduct(context,product,1);
        else catalog.deactivateVariant(context,variant,1);
    }
    private InventoryMovementCommand movement(InventoryMovementType type,long delta) {
        return new InventoryMovementCommand(actor,tenant,UUID.randomUUID(),variant,type,delta,"STOCK_CHANGE",UUID.randomUUID());
    }
    private CreateOrderCommand command(int quantity) {
        return new CreateOrderCommand(tenant,UUID.randomUUID(),List.of(new CreateOrderCommand.Item(variant,quantity)),
                TestCreateOrderIdempotencyKeyDigests.from("admin-race:"+UUID.randomUUID()));
    }
    private long quantity(String column) { return jdbc.queryForObject("SELECT "+column+" FROM inventory.inventory_positions WHERE tenant_id=? AND variant_id=?",Long.class,tenant,variant); }
    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE tenant_id=?",Long.class,tenant); }
    private void awaitLock(AtomicInteger pid) {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(4)).until(()->jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE pid=? AND wait_event_type='Lock'",Integer.class,pid.get())==1);
    }
    private static void await(CountDownLatch latch) {
        try { if(!latch.await(10,TimeUnit.SECONDS)) throw new AssertionError("Held transaction not released"); }
        catch(InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
    }
    enum InventoryChange { RECEIPT, ADJUSTMENT, SAFETY, POLICY }
    enum CatalogChange { PRODUCT, VARIANT }
}
