package io.github.piresrenan.orderhub.orders.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderAllocationOutcome;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderCommand;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderResult;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderUseCase;
import io.github.piresrenan.orderhub.orders.domain.model.Order;
import io.github.piresrenan.orderhub.orders.domain.model.OrderItem;
import io.github.piresrenan.orderhub.security.application.model.TrustedTenantContext;

class OrderControllerTrustedTenantContextTest {

    @Test
    void createsOrderCommandFromTrustedTenantContext() {

        var trustedTenantId =
                UUID.randomUUID();

        var customerId =
                UUID.randomUUID();

        var variantId =
                UUID.randomUUID();

        var capturedCommand =
                new AtomicReference<CreateOrderCommand>();

        CreateOrderUseCase createOrder =
                command -> {

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
                        createOrder,
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
                "trusted-tenant-context-test");

        controller.create(
                new TrustedTenantContext(
                        trustedTenantId),
                headers,
                request);

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
    void createEndpointDeclaresTrustedTenantContextInsteadOfRawTenantUuid()
            throws Exception {

        var createMethod =
                OrderController.class.getDeclaredMethod(
                        "create",
                        TrustedTenantContext.class,
                        HttpHeaders.class,
                        CreateOrderRequest.class);

        assertThat(
                createMethod.getParameterTypes())
                .containsExactly(
                        TrustedTenantContext.class,
                        HttpHeaders.class,
                        CreateOrderRequest.class);
    }
}