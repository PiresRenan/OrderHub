package io.github.piresrenan.orderhub.orders.adapter.in.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderCommand;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderUseCase;
import io.github.piresrenan.orderhub.security.application.model.TrustedTenantContext;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/orders")
public final class OrderController {

    private final CreateOrderUseCase createOrderUseCase;
    private final int maxItems;

    /**
     * Creates the HTTP adapter using only the application input port and an
     * externalized technical request-size boundary.
     *
     * <p>
     * The item limit protects application resources and is intentionally kept
     * outside the domain because it is not a business invariant.
     * </p>
     *
     * @param createOrderUseCase application boundary responsible for order creation
     * @param maxItems technical maximum number of items accepted by one request
     */
    public OrderController(
            CreateOrderUseCase createOrderUseCase,
            @Value("${orderhub.orders.http.max-items}") int maxItems) {

        if (maxItems < 1) {
            throw new IllegalArgumentException(
                    "orderhub.orders.http.max-items must be greater than zero");
        }

        this.createOrderUseCase =
                createOrderUseCase;

        this.maxItems =
                maxItems;
    }

    /**
     * Receives a validated order creation request and translates the HTTP
     * contract into the application command.
     *
     * @param tenantContext trusted Tenant context established by Security
     * @param request validated HTTP payload
     * @return created Order and its independent Inventory allocation outcome
     */
    @PostMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderResponse> create(
            TrustedTenantContext tenantContext,
            @Valid @RequestBody CreateOrderRequest request) {

        if (request.items().size() > maxItems) {
            throw new OrderRequestTooLargeException();
        }

        var items =
                request.items()
                        .stream()
                        .map(item ->
                                new CreateOrderCommand.Item(
                                        item.variantId(),
                                        item.quantity()))
                        .toList();

        var command =
                new CreateOrderCommand(
                        tenantContext.tenantId(),
                        request.customerId(),
                        items);

        var result =
                createOrderUseCase.create(
                        command);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(
                        OrderResponse.from(
                                result));
    }
}