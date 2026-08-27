package io.github.piresrenan.orderhub.orders.adapter.in.web;

import java.util.List;
import java.util.UUID;

import io.github.piresrenan.orderhub.orders.domain.model.Order;
import io.github.piresrenan.orderhub.orders.domain.model.OrderStatus;

public record OrderResponse(
                UUID id,
                UUID tenantId,
                UUID customerId,
                OrderStatus status,
                List<Item> items) {

        public record Item(
                        UUID productId,
                        int quantity) {
        }

        /**
         * Maps the internal domain aggregate to the public HTTP response contract.
         *
         * <p>
         * The explicit mapping prevents domain objects from becoming accidental API
         * contracts and makes future response evolution independent from domain model
         * changes.
         * </p>
         *
         * @param order domain aggregate returned by the application use case
         * @return representation safe for serialization by the HTTP adapter
         */
        public static OrderResponse from(Order order) {
                var items = order.items().stream()
                                .map(item -> new Item(
                                                item.productId(),
                                                item.quantity()))
                                .toList();

                return new OrderResponse(
                                order.id(),
                                order.tenantId(),
                                order.customerId(),
                                order.status(),
                                items);
        }
}