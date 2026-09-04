package io.github.piresrenan.orderhub.orders.adapter.in.web;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.piresrenan.orderhub.orders.application.port.in.CreateCustomerOrderUseCase;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderCommand;
import io.github.piresrenan.orderhub.orders.application.port.in.ViewCustomerOrderUseCase;
import io.github.piresrenan.orderhub.security.application.model.TrustedActorContext;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/orders")
public final class OrderController {

    private final CreateCustomerOrderUseCase createCustomerOrderUseCase;
    private final ViewCustomerOrderUseCase viewCustomerOrderUseCase;
    private final int maxItems;

    /**
     * Creates the HTTP adapter using only the Customer self-service application
     * input port and an externalized technical request-size boundary.
     *
     * <p>
     * The item limit protects application resources and is intentionally kept
     * outside the domain because it is not a business invariant.
     * </p>
     *
     * @param createCustomerOrderUseCase Customer self-service application boundary
     * @param viewCustomerOrderUseCase Customer own-Order read boundary
     * @param maxItems technical maximum number of items accepted by one request
     */
    public OrderController(
            CreateCustomerOrderUseCase createCustomerOrderUseCase,
            ViewCustomerOrderUseCase viewCustomerOrderUseCase,
            @Value("${orderhub.orders.http.max-items}") int maxItems) {

        if (maxItems < 1) {
            throw new IllegalArgumentException(
                    "orderhub.orders.http.max-items must be greater than zero");
        }

        this.createCustomerOrderUseCase =
                createCustomerOrderUseCase;

        this.viewCustomerOrderUseCase =
                viewCustomerOrderUseCase;

        this.maxItems =
                maxItems;
    }

    /**
     * Receives a validated Customer-originated Order creation request and
     * translates the HTTP contract into the application command.
     *
     * @param actorContext trusted User/Tenant context established by Security
     * @param headers request headers containing the validated idempotency identity
     * @param request validated HTTP payload whose customerId is only a selector
     * @return created Order and its independent Inventory allocation outcome
     */
    @PostMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderResponse> create(
            TrustedActorContext actorContext,
            @RequestHeader HttpHeaders headers,
            @Valid @RequestBody CreateOrderRequest request) {

        var idempotencyKeyDigest =
                OrderIdempotencyKeyHeader.requireValid(
                        headers);

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
                        actorContext.tenantId(),
                        request.customerId(),
                        items,
                        idempotencyKeyDigest);

        var result =
                createCustomerOrderUseCase.create(
                        actorContext.userId(),
                        command);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(
                        OrderResponse.from(
                                result));
    }

    /**
     * Returns one Customer-owned Order using only the trusted actor context
     * and the Order identifier supplied by the resource path.
     *
     * @param actorContext trusted User/Tenant context established by Security
     * @param orderId requested Order identifier
     * @return authorized persisted Order representation
     */
    @GetMapping(
            value = "/{orderId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderViewResponse> view(
            TrustedActorContext actorContext,
            @PathVariable UUID orderId) {

        var order =
                viewCustomerOrderUseCase.view(
                        actorContext.userId(),
                        actorContext.tenantId(),
                        orderId);

        return ResponseEntity
                .ok(
                        OrderViewResponse.from(
                                order));
    }
}
