package io.github.piresrenan.orderhub.orders.application.port.in;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.github.piresrenan.orderhub.orders.adapter.out.observability.micrometer.MicrometerObservedCreateOrderUseCase;
import io.github.piresrenan.orderhub.orders.domain.model.Order;
import io.github.piresrenan.orderhub.orders.domain.model.OrderItem;

class CreateOrderResultExecutionKindContractTest {

    private static final String ALLOCATION_METRIC =
            "orderhub.orders.create.allocation";

    private static final UUID TENANT_ID =
            UUID.fromString(
                    "10000000-0000-0000-0000-000000000001");

    private static final UUID ORDER_ID =
            UUID.fromString(
                    "20000000-0000-0000-0000-000000000001");

    private static final UUID VARIANT_ID =
            UUID.fromString(
                    "30000000-0000-0000-0000-000000000001");

    private static final UUID CUSTOMER_ID =
            UUID.fromString(
                    "50000000-0000-0000-0000-000000000001");

    @Test
    void replayRemainsSuccessfulWithoutBecomingNewCreationThroughput() {

        var order =
                order();

        var defaultFirstExecution =
                new CreateOrderResult(
                        order,
                        CreateOrderAllocationOutcome.FULLY_ALLOCATED);

        assertThat(defaultFirstExecution.executionKind())
                .isEqualTo(
                        CreateOrderExecutionKind.FIRST_EXECUTION);

        var replay =
                new CreateOrderResult(
                        order,
                        CreateOrderAllocationOutcome.FULLY_ALLOCATED,
                        CreateOrderExecutionKind.REPLAY);

        var registry =
                new SimpleMeterRegistry();

        CreateOrderUseCase delegate =
                command ->
                        replay;

        var observed =
                new MicrometerObservedCreateOrderUseCase(
                        delegate,
                        registry);

        var returned =
                observed.create(
                        command());

        assertThat(returned)
                .isSameAs(
                        replay);

        assertThat(returned.executionKind())
                .isEqualTo(
                        CreateOrderExecutionKind.REPLAY);

        assertThat(
                registry.getMeters()
                        .stream()
                        .filter(meter ->
                                ALLOCATION_METRIC.equals(
                                        meter.getId()
                                                .getName()))
                        .toList())
                .as(
                        "Replay is successful request traffic but not new Order/Inventory throughput")
                .isEmpty();
    }

    private static CreateOrderCommand command() {

        return new CreateOrderCommand(
                TENANT_ID,
                CUSTOMER_ID,
                List.of(
                        new CreateOrderCommand.Item(
                                VARIANT_ID,
                                1)),
                CreateOrderIdempotencyKeyDigest.of(
                        new byte[32]));
    }

    private static Order order() {

        return Order.create(
                ORDER_ID,
                TENANT_ID,
                CUSTOMER_ID,
                List.of(
                        new OrderItem(
                                VARIANT_ID,
                                1)));
    }
}
