package io.github.piresrenan.orderhub.orders.domain.model;

import java.util.List;
import java.util.UUID;

public final class Order {
    private final UUID id;
    private final UUID tenantId;
    private final UUID customerId;
    private final List<OrderItem> items;
    private final OrderStatus status;

    private Order(
        UUID id,
        UUID tenantId,
        UUID customerId,
        List<OrderItem> items,
        OrderStatus status
    ) {
        this.id = id;
        this.tenantId = tenantId;
        this.customerId = customerId;
        this.items = items;
        this.status = status;
    }

    public static Order create(UUID id, UUID tenantId, UUID customerId, List<OrderItem> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("O pedido deve conter ao menos um item.");
        }
        return new Order(id, tenantId, customerId, items, OrderStatus.CREATED);
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    public OrderStatus getStatus() {
        return status;
    }
}