package io.github.piresrenan.orderhub.orders.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class OrderTest {

    @Test
    void createsOrderWithCreatedStatus() {
        // Why: every newly accepted aggregate must start from one deterministic state.
        // Covers: Order factory lifecycle initialization.
        // Prevents: caller-controlled or inconsistent initial workflow states.

        var order = createValidOrder(validItems());

        assertThat(order.status()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    void rejectsOrderWithoutItems() {
        // Why: an empty order has no valid business meaning in the current domain.
        // Covers: aggregate minimum-content invariant.
        // Prevents: meaningless records reaching persistence and downstream services.

        assertThatThrownBy(() -> createValidOrder(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Order must contain at least one item");
    }

    @Test
    void rejectsMissingOrderId() {
        // Why: aggregates without identity cannot be safely persisted or referenced.
        // Covers: aggregate identity invariant.
        // Prevents: null keys and inconsistent repository behavior.

        assertThatThrownBy(() -> Order.create(
                null,
                UUID.randomUUID(),
                UUID.randomUUID(),
                validItems()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Order id is required");
    }

    @Test
    void rejectsMissingTenantId() {
        // Why: every order must belong to an explicit tenant boundary.
        // Covers: tenant ownership invariant.
        // Prevents: future unscoped records and cross-tenant data ambiguity.

        assertThatThrownBy(() -> Order.create(
                UUID.randomUUID(),
                null,
                UUID.randomUUID(),
                validItems()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Tenant id is required");
    }

    @Test
    void rejectsMissingCustomerId() {
        // Why: the current business model requires explicit customer ownership.
        // Covers: customer association invariant.
        // Prevents: orphan orders that cannot be attributed consistently.

        assertThatThrownBy(() -> Order.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                validItems()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Customer id is required");
    }

    @Test
    void rejectsNullItemsInsideCollection() {
        // Why: collection cardinality alone does not guarantee valid elements.
        // Covers: null element validation.
        // Prevents: delayed NullPointerExceptions in pricing, persistence or serialization.

        var items = new ArrayList<OrderItem>();
        items.add(null);

        assertThatThrownBy(() -> createValidOrder(items))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Order items must not contain null values");
    }

    @Test
    void defensivelyCopiesIncomingItems() {
        // Why: callers must not mutate aggregate state after successful construction.
        // Covers: ownership and defensive-copy semantics.
        // Prevents: invariant corruption through external mutable references.

        var source = new ArrayList<>(validItems());
        var order = createValidOrder(source);

        source.clear();

        assertThat(order.items()).hasSize(1);
    }

    @Test
    void exposesUnmodifiableItems() {
        // Why: aggregate state changes must eventually happen through domain behavior.
        // Covers: immutable collection exposure.
        // Prevents: uncontrolled state mutation outside the aggregate root.

        var order = createValidOrder(validItems());

        assertThatThrownBy(() -> order.items().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * Creates a valid aggregate while allowing each test to vary only the items
     * relevant to the invariant under test.
     */
    private Order createValidOrder(List<OrderItem> items) {
        return Order.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                items);
    }

    /**
     * Provides one valid item for tests that are not exercising item validation.
     */
    private List<OrderItem> validItems() {
        return List.of(
                new OrderItem(
                        UUID.randomUUID(),
                        1));
    }
}