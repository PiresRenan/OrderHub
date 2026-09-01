package io.github.piresrenan.orderhub.orders.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderCommand;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderUseCase;
import io.github.piresrenan.orderhub.orders.domain.model.Order;
import io.github.piresrenan.orderhub.orders.domain.model.OrderItem;
import io.github.piresrenan.orderhub.security.application.model.TrustedTenantContext;

class OrderControllerTrustedTenantContextTest {

    @Test
    void createsOrderCommandFromTrustedTenantContext() {
        // Why: Orders must receive Tenant authority only after Security has
        // verified the authenticated User's membership.
        // Covers: TrustedTenantContext -> CreateOrderCommand tenant propagation.
        // Prevents: the controller constructing application commands directly
        // from an untrusted X-Tenant-Id value.

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

                    return Order.create(
                            UUID.randomUUID(),
                            command.tenantId(),
                            command.customerId(),
                            List.of(
                                    new OrderItem(
                                            variantId,
                                            2)));
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

        controller.create(
                new TrustedTenantContext(
                        trustedTenantId),
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

        // Why: the controller signature itself is part of the trust boundary.
        // Covers: removal of the raw Tenant UUID parameter from OrderController.
        // Prevents: future refactoring from silently restoring X-Tenant-Id as
        // authoritative controller input.

        var createMethod =
                OrderController.class.getDeclaredMethod(
                        "create",
                        TrustedTenantContext.class,
                        CreateOrderRequest.class);

        assertThat(
                createMethod.getParameterTypes())
                .containsExactly(
                        TrustedTenantContext.class,
                        CreateOrderRequest.class);
    }
}
