package io.github.piresrenan.orderhub.orders.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import io.github.piresrenan.orderhub.orders.application.port.in.CreateCustomerOrderUseCase;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderAllocationOutcome;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderCommand;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderResult;
import io.github.piresrenan.orderhub.orders.domain.model.Order;
import io.github.piresrenan.orderhub.orders.domain.model.OrderItem;
import io.github.piresrenan.orderhub.security.application.model.TrustedActorContext;

class OrderControllerTrustedActorContextTest {

    @Test
    void createsCustomerOrderFromTrustedActorContext() {

        var trustedUserId =
                UUID.randomUUID();

        var trustedTenantId =
                UUID.randomUUID();

        var customerId =
                UUID.randomUUID();

        var variantId =
                UUID.randomUUID();

        var capturedActorUserId =
                new AtomicReference<UUID>();

        var capturedCommand =
                new AtomicReference<CreateOrderCommand>();

        CreateCustomerOrderUseCase customerCreate =
                (actorUserId, command) -> {

                    capturedActorUserId.set(
                            actorUserId);

                    capturedCommand.set(
                            command);

                    var order =
                            Order.create(
                                    UUID.randomUUID(),
                                    command.tenantId(),
                                    command.customerId(),
                                    List.of(
                                            new OrderItem(
                                                    variantId,
                                                    2)));

                    return new CreateOrderResult(
                            order,
                            CreateOrderAllocationOutcome.FULLY_ALLOCATED);
                };

        var controller =
                new OrderController(
                        customerCreate,
                        (unusedViewActorUserId, unusedViewTenantId, unusedViewOrderId) -> {
                            throw new AssertionError(
                                    "Create-path fixture must not invoke Order view");
                        },
                        10);

        var request =
                new CreateOrderRequest(
                        customerId,
                        List.of(
                                new CreateOrderRequest.Item(
                                        variantId,
                                        2)));

        var headers =
                new HttpHeaders();

        headers.add(
                OrderIdempotencyKeyHeader.NAME,
                "trusted-actor-context-test");

        controller.create(
                new TrustedActorContext(
                        trustedUserId,
                        trustedTenantId),
                headers,
                request);

        assertThat(capturedActorUserId)
                .hasValue(
                        trustedUserId);

        assertThat(capturedCommand)
                .hasValueSatisfying(command -> {

                    assertThat(command.tenantId())
                            .isEqualTo(
                                    trustedTenantId);

                    assertThat(command.customerId())
                            .isEqualTo(
                                    customerId);

                    assertThat(command.items())
                            .singleElement()
                            .satisfies(item -> {

                                assertThat(item.variantId())
                                        .isEqualTo(
                                                variantId);

                                assertThat(item.quantity())
                                        .isEqualTo(
                                                2);
                            });
                });
    }

    @Test
    void createEndpointDeclaresTrustedActorContext()
            throws Exception {

        var createMethod =
                OrderController.class.getDeclaredMethod(
                        "create",
                        TrustedActorContext.class,
                        HttpHeaders.class,
                        CreateOrderRequest.class);

        assertThat(
                createMethod.getParameterTypes())
                .containsExactly(
                        TrustedActorContext.class,
                        HttpHeaders.class,
                        CreateOrderRequest.class);
    }
}
