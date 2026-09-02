package io.github.piresrenan.orderhub.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.orders.adapter.out.persistence.postgresql.PostgreSqlCreateOrderIdempotencyRepository;
import io.github.piresrenan.orderhub.orders.application.idempotency.CreateOrderRequestFingerprint;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderCommand;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderIdempotencyKeyDigest;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderUseCase;
import io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyAcquisition;
import io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyCompletion;
import io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyPersistenceException;
import io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyRepository;
import io.github.piresrenan.orderhub.orders.support.TestCreateOrderIdempotencyKeyDigests;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;

@SpringBootTest(properties = {
        "orderhub.security.jwt.issuer=https://issuer.completion-failure.test",
        "orderhub.security.jwt.audience=orderhub-api",
        "orderhub.security.jwt.jwk-set-uri=http://127.0.0.1:1/unused-completion-failure-jwks"
})
@Import({
        PostgreSqlTestConfiguration.class,
        CreateOrderIdempotencyCompletionFailureAcceptanceTest.FailureConfiguration.class
})
class CreateOrderIdempotencyCompletionFailureAcceptanceTest {

    private static final UUID TENANT_ID =
            UUID.fromString(
                    "10000000-0000-0000-0000-000000000001");

    private static final UUID PRODUCT_ID =
            UUID.fromString(
                    "20000000-0000-0000-0000-000000000001");

    private static final UUID VARIANT_ID =
            UUID.fromString(
                    "30000000-0000-0000-0000-000000000001");

    private static final UUID CUSTOMER_ID =
            UUID.fromString(
                    "50000000-0000-0000-0000-000000000001");

    @Autowired
    private CreateOrderUseCase createOrder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FaultInjectingIdempotencyRepository idempotencyFaults;

    @BeforeEach
    void resetState() {

        idempotencyFaults.disableFailure();

        jdbcTemplate.update(
                """
                TRUNCATE TABLE
                    orders.order_request_idempotency,
                    inventory.inventory_commitments,
                    inventory.inventory_positions,
                    inventory.tenant_policies,
                    catalog.media,
                    catalog.variant_base_prices,
                    catalog.product_variant_attributes,
                    catalog.product_categories,
                    catalog.product_variants,
                    catalog.categories,
                    catalog.category_hierarchy_guards,
                    catalog.products,
                    orders.order_items,
                    orders.orders
                """);

        seedBusinessState();
    }

    @Test
    void completionPersistenceFailureRollsBackEveryBusinessEffectAndReleasesKey() {

        var command =
                command();

        idempotencyFaults.failNextCompletion();

        assertThatThrownBy(() ->
                createOrder.create(
                        command))
                .isInstanceOf(
                        CreateOrderIdempotencyPersistenceException.class);

        /*
         * The failure occurs only after acquisition, Order persistence,
         * Catalog validation and Inventory commitment have already executed.
         *
         * Nothing from that transaction may remain durable.
         */
        assertThat(orderCount())
                .isZero();

        assertThat(orderItemCount())
                .isZero();

        assertThat(commitmentCount())
                .isZero();

        assertThat(committedQuantity())
                .isZero();

        assertThat(idempotencyCount())
                .isZero();

        assertThat(processingIdempotencyCount())
                .isZero();

        /*
         * Because PROCESSING rolled back with the business transaction, the
         * exact same durable key identity is available for a later retry.
         */
        var retry =
                createOrder.create(
                        command);

        assertThat(retry.order().tenantId())
                .isEqualTo(
                        TENANT_ID);

        assertThat(retry.order().customerId())
                .isEqualTo(
                        CUSTOMER_ID);

        assertThat(orderCount())
                .isEqualTo(
                        1);

        assertThat(orderItemCount())
                .isEqualTo(
                        1);

        assertThat(commitmentCount())
                .isEqualTo(
                        1);

        assertThat(committedQuantity())
                .isEqualTo(
                        2);

        assertThat(completedIdempotencyCount())
                .isEqualTo(
                        1);

        assertThat(processingIdempotencyCount())
                .isZero();
    }

    private static CreateOrderCommand command() {

        CreateOrderIdempotencyKeyDigest digest =
                TestCreateOrderIdempotencyKeyDigests.from(
                        "completion-failure-recovery");

        return new CreateOrderCommand(
                TENANT_ID,
                CUSTOMER_ID,
                List.of(
                        new CreateOrderCommand.Item(
                                VARIANT_ID,
                                2)),
                digest);
    }

    private void seedBusinessState() {

        jdbcTemplate.update(
                """
                INSERT INTO catalog.products (
                    tenant_id,
                    id,
                    name,
                    slug,
                    description,
                    status
                )
                VALUES (
                    ?,
                    ?,
                    'Completion failure product',
                    'completion-failure-product',
                    NULL,
                    'ACTIVE'
                )
                """,
                TENANT_ID,
                PRODUCT_ID);

        jdbcTemplate.update(
                """
                INSERT INTO catalog.product_variants (
                    tenant_id,
                    id,
                    product_id,
                    sku,
                    status
                )
                VALUES (
                    ?,
                    ?,
                    ?,
                    'COMPLETION-FAILURE-VARIANT',
                    'ACTIVE'
                )
                """,
                TENANT_ID,
                VARIANT_ID,
                PRODUCT_ID);

        jdbcTemplate.update(
                """
                INSERT INTO inventory.tenant_policies (
                    tenant_id,
                    policy
                )
                VALUES (?, 'DENY')
                """,
                TENANT_ID);

        jdbcTemplate.update(
                """
                INSERT INTO inventory.inventory_positions (
                    tenant_id,
                    variant_id,
                    on_hand,
                    committed,
                    backordered,
                    safety_stock
                )
                VALUES (?, ?, 10, 0, 0, 0)
                """,
                TENANT_ID,
                VARIANT_ID);
    }

    private long orderCount() {

        return scalar(
                """
                SELECT COUNT(*)
                FROM orders.orders
                WHERE tenant_id = ?
                """,
                TENANT_ID);
    }

    private long orderItemCount() {

        return scalar(
                """
                SELECT COUNT(*)
                FROM orders.order_items
                WHERE tenant_id = ?
                """,
                TENANT_ID);
    }

    private long commitmentCount() {

        return scalar(
                """
                SELECT COUNT(*)
                FROM inventory.inventory_commitments
                WHERE tenant_id = ?
                  AND variant_id = ?
                """,
                TENANT_ID,
                VARIANT_ID);
    }

    private long committedQuantity() {

        return scalar(
                """
                SELECT committed
                FROM inventory.inventory_positions
                WHERE tenant_id = ?
                  AND variant_id = ?
                """,
                TENANT_ID,
                VARIANT_ID);
    }

    private long idempotencyCount() {

        return scalar(
                """
                SELECT COUNT(*)
                FROM orders.order_request_idempotency
                WHERE tenant_id = ?
                """,
                TENANT_ID);
    }

    private long completedIdempotencyCount() {

        return scalar(
                """
                SELECT COUNT(*)
                FROM orders.order_request_idempotency
                WHERE tenant_id = ?
                  AND state = 'COMPLETED'
                """,
                TENANT_ID);
    }

    private long processingIdempotencyCount() {

        return scalar(
                """
                SELECT COUNT(*)
                FROM orders.order_request_idempotency
                WHERE tenant_id = ?
                  AND state = 'PROCESSING'
                """,
                TENANT_ID);
    }

    private long scalar(
            String sql,
            Object... arguments) {

        return jdbcTemplate.queryForObject(
                sql,
                Long.class,
                arguments);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FailureConfiguration {

        @Bean
        @Primary
        FaultInjectingIdempotencyRepository faultInjectingIdempotencyRepository(
                JdbcTemplate jdbcTemplate,
                @Value("${orderhub.orders.idempotency.acquisition-timeout}")
                Duration acquisitionTimeout) {

            return new FaultInjectingIdempotencyRepository(
                    new PostgreSqlCreateOrderIdempotencyRepository(
                            jdbcTemplate,
                            acquisitionTimeout));
        }
    }

    static final class FaultInjectingIdempotencyRepository
            implements CreateOrderIdempotencyRepository {

        private final CreateOrderIdempotencyRepository delegate;

        private final AtomicBoolean failNextCompletion =
                new AtomicBoolean();

        private FaultInjectingIdempotencyRepository(
                CreateOrderIdempotencyRepository delegate) {

            this.delegate =
                    delegate;
        }

        void failNextCompletion() {

            failNextCompletion.set(
                    true);
        }

        void disableFailure() {

            failNextCompletion.set(
                    false);
        }

        @Override
        public CreateOrderIdempotencyAcquisition acquire(
                UUID tenantId,
                CreateOrderIdempotencyKeyDigest keyDigest,
                CreateOrderRequestFingerprint fingerprint) {

            return delegate.acquire(
                    tenantId,
                    keyDigest,
                    fingerprint);
        }

        @Override
        public void complete(
                UUID tenantId,
                CreateOrderIdempotencyKeyDigest keyDigest,
                CreateOrderRequestFingerprint fingerprint,
                CreateOrderIdempotencyCompletion completion) {

            if (failNextCompletion.compareAndSet(
                    true,
                    false)) {

                throw new CreateOrderIdempotencyPersistenceException(
                        "synthetic completion persistence failure");
            }

            delegate.complete(
                    tenantId,
                    keyDigest,
                    fingerprint,
                    completion);
        }
    }
}
