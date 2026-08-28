package io.github.piresrenan.orderhub.orders.adapter.in.web;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderCommand;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderUseCase;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/orders")
public final class OrderController {

    private final CreateOrderUseCase createOrderUseCase;

    /**
     * Creates the HTTP adapter using only the application input port.
     *
     * @param createOrderUseCase application boundary responsible for order creation
     */
    public OrderController(CreateOrderUseCase createOrderUseCase) {
        this.createOrderUseCase = createOrderUseCase;
    }

    /**
     * Receives a validated order creation request and translates the HTTP contract
     * into the application command.
     *
     * <p>The tenant identifier currently represents request context only. It must
     * not be considered an authorization mechanism until authenticated tenant
     * resolution is implemented.</p>
     *
     * @param tenantId tenant context supplied by the caller
     * @param request validated HTTP payload
     * @return the created order represented by the public HTTP response contract
     */
    @PostMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderResponse> create(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @Valid @RequestBody CreateOrderRequest request) {

        var items = request.items().stream()
                .map(item -> new CreateOrderCommand.Item(
                        item.productId(),
                        item.quantity()))
                .toList();

        var command = new CreateOrderCommand(
                tenantId,
                request.customerId(),
                items);

        var order = createOrderUseCase.create(command);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(OrderResponse.from(order));
    }
}