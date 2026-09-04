package io.github.piresrenan.orderhub.orders.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderAllocationOutcome;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderCommand;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderResult;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateCustomerOrderUseCase;
import io.github.piresrenan.orderhub.orders.domain.model.Order;
import io.github.piresrenan.orderhub.orders.domain.model.OrderItem;
import io.github.piresrenan.orderhub.security.application.model.TrustedActorContext;

class OrderIdempotencyKeyApplicationBoundaryTest {

    @Test
    void propagatesOnlySha256IdempotencyIdentityIntoApplication()
            throws Exception {

        var tenantId =
                UUID.fromString(
                        "11111111-1111-1111-1111-111111111111");

        var customerId =
                UUID.fromString(
                        "22222222-2222-2222-2222-222222222222");

        var variantId =
                UUID.fromString(
                        "33333333-3333-3333-3333-333333333333");

        var rawKey =
                "client-generated-order-key-001";

        var expectedDigest =
                MessageDigest
                        .getInstance(
                                "SHA-256")
                        .digest(
                                rawKey.getBytes(
                                        StandardCharsets.UTF_8));

        assertThat(expectedDigest)
                .hasSize(
                        32);

        var capturedCommand =
                new AtomicReference<CreateOrderCommand>();

        CreateCustomerOrderUseCase createOrder =
                (actorUserId, command) -> {

                    capturedCommand.set(
                            command);

                    var order =
                            Order.create(
                                    UUID.fromString(
                                            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
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
                        (unusedViewActorUserId, unusedViewTenantId, unusedViewOrderId) -> {
                            throw new AssertionError(
                                    "Create-path fixture must not invoke Order view");
                        },
                        10);

        var headers =
                new HttpHeaders();

        headers.add(
                OrderIdempotencyKeyHeader.NAME,
                rawKey);

        var request =
                new CreateOrderRequest(
                        customerId,
                        List.of(
                                new CreateOrderRequest.Item(
                                        variantId,
                                        2)));

        controller.create(
                new TrustedActorContext(
                        UUID.randomUUID(),
                        tenantId),
                headers,
                request);

        assertThat(capturedCommand.get())
                .as(
                        "A valid Idempotency-Key must reach application as a digest identity")
                .isNotNull();

        var command =
                capturedCommand.get();

        var digestComponent =
                Arrays.stream(
                                CreateOrderCommand.class
                                        .getRecordComponents())
                        .filter(component ->
                                component
                                        .getName()
                                        .equals(
                                                "idempotencyKeyDigest"))
                        .findFirst();

        assertThat(digestComponent)
                .as(
                        "CreateOrderCommand must expose idempotencyKeyDigest")
                .isPresent();

        var component =
                digestComponent
                        .orElseThrow();

        assertThat(
                component
                        .getType()
                        .getSimpleName())
                .as(
                        "The application boundary must use a dedicated create-Order digest value")
                .isEqualTo(
                        "CreateOrderIdempotencyKeyDigest");

        assertThat(component.getType())
                .as(
                        "The raw Idempotency-Key must not cross as String")
                .isNotEqualTo(
                        String.class);

        var digestValue =
                component
                        .getAccessor()
                        .invoke(
                                command);

        assertThat(digestValue)
                .isNotNull();

        var bytesMethod =
                digestValue
                        .getClass()
                        .getMethod(
                                "bytes");

        var actualDigest =
                (byte[]) bytesMethod.invoke(
                        digestValue);

        assertThat(actualDigest)
                .as(
                        "SHA-256 digest must contain exactly 32 bytes")
                .hasSize(
                        32);

        assertThat(actualDigest)
                .as(
                        "Application identity must equal SHA-256(UTF-8(Idempotency-Key))")
                .containsExactly(
                        expectedDigest);

        for (var recordComponent :
                CreateOrderCommand.class
                        .getRecordComponents()) {

            var value =
                    recordComponent
                            .getAccessor()
                            .invoke(
                                    command);

            assertThat(value)
                    .as(
                            "CreateOrderCommand must not contain the raw Idempotency-Key")
                    .isNotEqualTo(
                            rawKey);
        }

        assertThat(command.toString())
                .as(
                        "CreateOrderCommand diagnostics must not expose the raw Idempotency-Key")
                .doesNotContain(
                        rawKey);
    }
}
