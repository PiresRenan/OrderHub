package io.github.piresrenan.orderhub.orders.adapter.out.persistence.postgresql;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.orders.application.port.out.OrderPersistenceException;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderRepository;
import io.github.piresrenan.orderhub.orders.domain.model.Order;
import io.github.piresrenan.orderhub.orders.domain.model.OrderItem;
import io.github.piresrenan.orderhub.orders.domain.model.OrderStatus;

/**
 * Persists and reconstructs Order aggregates using explicit PostgreSQL SQL.
 *
 * <p>
 * This adapter owns relational mapping only. Transaction demarcation belongs
 * to the calling application use case so one transaction can include multiple
 * participating persistence adapters.
 * </p>
 */
public final class PostgreSqlOrderRepository
        implements OrderRepository {

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
                variant_id,
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
                variant_id,
                quantity
            FROM orders.order_items
            WHERE tenant_id = ?
              AND order_id = ?
            ORDER BY line_number
            """;

    private final JdbcTemplate jdbcTemplate;

    public PostgreSqlOrderRepository(
            JdbcTemplate jdbcTemplate) {

        this.jdbcTemplate =
                Objects.requireNonNull(
                        jdbcTemplate,
                        "jdbcTemplate");
    }

    @Override
    public Order save(
            Order order) {

        try {
            insertOrderRoot(
                    order);

            insertOrderItems(
                    order);

            return order;
        } catch (DataAccessException exception) {
            throw new OrderPersistenceException(
                    exception);
        }
    }

    @Override
    public Optional<Order> findById(
            UUID tenantId,
            UUID orderId) {

        try {
            var root =
                    loadOrderRoot(
                            tenantId,
                            orderId);

            if (root.isEmpty()) {
                return Optional.empty();
            }

            var persistedRoot =
                    root.orElseThrow();

            var items =
                    loadOrderItems(
                            tenantId,
                            orderId);

            return Optional.of(
                    Order.rehydrate(
                            persistedRoot.id(),
                            persistedRoot.tenantId(),
                            persistedRoot.customerId(),
                            items,
                            persistedRoot.status()));
        } catch (DataAccessException exception) {
            throw new OrderPersistenceException(
                    exception);
        }
    }

    private void insertOrderRoot(
            Order order) {

        jdbcTemplate.update(
                INSERT_ORDER_SQL,
                order.tenantId(),
                order.id(),
                order.customerId(),
                order.status().name());
    }

    private void insertOrderItems(
            Order order) {

        for (int lineNumber = 0;
                lineNumber < order.items().size();
                lineNumber++) {

            var item =
                    order.items()
                            .get(lineNumber);

            jdbcTemplate.update(
                    INSERT_ITEM_SQL,
                    order.tenantId(),
                    order.id(),
                    lineNumber,
                    item.variantId(),
                    item.quantity());
        }
    }

    private Optional<PersistedOrderRoot> loadOrderRoot(
            UUID tenantId,
            UUID orderId) {

        var roots =
                jdbcTemplate.query(
                        FIND_ORDER_SQL,
                        (resultSet, rowNumber) ->
                                new PersistedOrderRoot(
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
                                                resultSet.getString(
                                                        "status"))),
                        tenantId,
                        orderId);

        return roots.stream()
                .findFirst();
    }

    private List<OrderItem> loadOrderItems(
            UUID tenantId,
            UUID orderId) {

        return jdbcTemplate.query(
                FIND_ITEMS_SQL,
                (resultSet, rowNumber) ->
                        new OrderItem(
                                resultSet.getObject(
                                        "variant_id",
                                        UUID.class),
                                resultSet.getInt(
                                        "quantity")),
                tenantId,
                orderId);
    }

    private record PersistedOrderRoot(
            UUID id,
            UUID tenantId,
            UUID customerId,
            OrderStatus status) {
    }
}