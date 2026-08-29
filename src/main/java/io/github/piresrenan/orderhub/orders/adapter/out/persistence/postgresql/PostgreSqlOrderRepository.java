package io.github.piresrenan.orderhub.orders.adapter.out.persistence.postgresql;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

import io.github.piresrenan.orderhub.orders.application.port.out.OrderRepository;
import io.github.piresrenan.orderhub.orders.domain.model.Order;
import io.github.piresrenan.orderhub.orders.domain.model.OrderItem;
import io.github.piresrenan.orderhub.orders.domain.model.OrderStatus;

/**
 * Persists and reconstructs Order aggregates using explicit PostgreSQL SQL.
 *
 * <p>This adapter owns relational mapping and transaction demarcation while the
 * application and domain layers remain independent of JDBC, Spring transactions
 * and PostgreSQL-specific concerns.</p>
 */
public final class PostgreSqlOrderRepository implements OrderRepository {

    private static final String INSERT_ORDER_SQL = """
            INSERT INTO orders.orders (
                tenant_id,
                id,
                customer_id,
                status
            )
            VALUES (?, ?, ?, ?)
            """;

    private static final String INSERT_ITEM_SQL = """
            INSERT INTO orders.order_items (
                tenant_id,
                order_id,
                line_number,
                product_id,
                quantity
            )
            VALUES (?, ?, ?, ?, ?)
            """;

    private static final String FIND_ORDER_SQL = """
            SELECT
                tenant_id,
                id,
                customer_id,
                status
            FROM orders.orders
            WHERE tenant_id = ?
              AND id = ?
            """;

    private static final String FIND_ITEMS_SQL = """
            SELECT
                product_id,
                quantity
            FROM orders.order_items
            WHERE tenant_id = ?
              AND order_id = ?
            ORDER BY line_number
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionOperations transactionOperations;

    /**
     * Creates a PostgreSQL repository using externally provided JDBC and
     * transaction infrastructure.
     *
     * <p>The adapter itself is deliberately free of Spring component annotations.
     * Runtime composition remains the responsibility of the application
     * configuration layer.</p>
     *
     * @param jdbcTemplate JDBC operations configured for the Orders database
     * @param transactionOperations transaction boundary used for aggregate writes
     */
    public PostgreSqlOrderRepository(
            JdbcTemplate jdbcTemplate,
            TransactionOperations transactionOperations) {

        this.jdbcTemplate = jdbcTemplate;
        this.transactionOperations = transactionOperations;
    }

    /**
     * Persists one complete Order aggregate atomically.
     *
     * <p>The root and every owned item are written inside the same database
     * transaction. Any failure during item persistence therefore causes the root
     * write and all preceding item writes to roll back together.</p>
     *
     * @param order valid aggregate to persist
     * @return the same aggregate after successful persistence
     */
    @Override
    public Order save(Order order) {
        transactionOperations.executeWithoutResult(transactionStatus -> {
            insertOrderRoot(order);
            insertOrderItems(order);
        });

        return order;
    }

    /**
     * Loads one complete Order aggregate inside the requested tenant boundary.
     *
     * <p>Absence of the root returns an empty Optional. When the root exists, its
     * items are loaded in persisted line order and the domain aggregate is
     * reconstructed through {@link Order#rehydrate(UUID, UUID, UUID, List,
     * OrderStatus)}.</p>
     *
     * @param tenantId tenant boundary in which the Order must exist
     * @param orderId aggregate identifier
     * @return reconstructed Order when found in the requested tenant, otherwise
     *         an empty Optional
     */
    @Override
    public Optional<Order> findById(
            UUID tenantId,
            UUID orderId) {

        var root = loadOrderRoot(
                tenantId,
                orderId);

        if (root.isEmpty()) {
            return Optional.empty();
        }

        var persistedRoot = root.orElseThrow();

        var items = loadOrderItems(
                tenantId,
                orderId);

        return Optional.of(
                Order.rehydrate(
                        persistedRoot.id(),
                        persistedRoot.tenantId(),
                        persistedRoot.customerId(),
                        items,
                        persistedRoot.status()));
    }

    /**
     * Inserts the relational root representation of one Order.
     *
     * @param order aggregate whose root state must be persisted
     */
    private void insertOrderRoot(Order order) {
        jdbcTemplate.update(
                INSERT_ORDER_SQL,
                order.tenantId(),
                order.id(),
                order.customerId(),
                order.status().name());
    }

    /**
     * Inserts all Order items while preserving their aggregate list position as
     * the relational line number.
     *
     * @param order aggregate whose owned items must be persisted
     */
    private void insertOrderItems(Order order) {
        for (int lineNumber = 0;
                lineNumber < order.items().size();
                lineNumber++) {

            var item = order.items().get(lineNumber);

            jdbcTemplate.update(
                    INSERT_ITEM_SQL,
                    order.tenantId(),
                    order.id(),
                    lineNumber,
                    item.productId(),
                    item.quantity());
        }
    }

    /**
     * Loads the relational Order root using its complete tenant-scoped identity.
     *
     * @param tenantId tenant boundary to search
     * @param orderId aggregate identifier to search
     * @return persisted root state when present, otherwise an empty Optional
     */
    private Optional<PersistedOrderRoot> loadOrderRoot(
            UUID tenantId,
            UUID orderId) {

        var roots = jdbcTemplate.query(
                FIND_ORDER_SQL,
                (resultSet, rowNumber) -> new PersistedOrderRoot(
                        resultSet.getObject(
                                "id",
                                UUID.class),
                        resultSet.getObject(
                                "tenant_id",
                                UUID.class),
                        resultSet.getObject(
                                "customer_id",
                                UUID.class),
                        OrderStatus.valueOf(
                                resultSet.getString("status"))),
                tenantId,
                orderId);

        return roots.stream()
                .findFirst();
    }

    /**
     * Loads all items owned by one Order in their persisted line-number order.
     *
     * @param tenantId owning tenant identifier
     * @param orderId owning Order identifier
     * @return ordered domain items reconstructed from relational rows
     */
    private List<OrderItem> loadOrderItems(
            UUID tenantId,
            UUID orderId) {

        return jdbcTemplate.query(
                FIND_ITEMS_SQL,
                (resultSet, rowNumber) -> new OrderItem(
                        resultSet.getObject(
                                "product_id",
                                UUID.class),
                        resultSet.getInt("quantity")),
                tenantId,
                orderId);
    }

    /**
     * Carries relational root state inside the PostgreSQL adapter before domain
     * reconstruction.
     *
     * <p>This representation is private so JDBC persistence structures cannot
     * escape into the application or domain layers.</p>
     *
     * @param id aggregate identifier
     * @param tenantId owning tenant identifier
     * @param customerId persisted customer association
     * @param status persisted lifecycle state
     */
    private record PersistedOrderRoot(
            UUID id,
            UUID tenantId,
            UUID customerId,
            OrderStatus status) {
    }
}
