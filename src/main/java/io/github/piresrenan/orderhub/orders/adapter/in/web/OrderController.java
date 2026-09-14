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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/orders")
@Tag(name = "Orders", description = "Customer self-service Order creation and read operations.")
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
    @Operation(
            operationId = "ordersCreate",
            summary = "Create a Customer order",
            description = "Creates an Order on behalf of the authenticated Customer actor, who must hold "
                    + "CUSTOMER_ORDERS_CREATE and own the referenced customerId. A successful new execution "
                    + "and a durable replay of an already completed request with the same Idempotency-Key and "
                    + "canonical content both return 201 with the same representation. Reusing the same "
                    + "Idempotency-Key with different canonical request content returns 422. A concurrent "
                    + "request still acquiring the same Idempotency-Key returns 409. A request exceeding the "
                    + "technical item-count limit returns 413. A missing or syntactically invalid "
                    + "Idempotency-Key returns 400.",
            parameters = {
                    @Parameter(
                            name = "X-Tenant-Id",
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "Active Tenant selector, validated against the authenticated internal "
                                    + "identity, active membership and Tenant state.",
                            schema = @Schema(type = "string", format = "uuid")),
                    @Parameter(
                            name = OrderIdempotencyKeyHeader.NAME,
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "Opaque idempotency identity for this create-Order request. 1 to 128 "
                                    + "visible ASCII characters (0x21-0x7E) excluding comma; whitespace is forbidden. Reusing this "
                                    + "key with different canonical request content is rejected.",
                            schema = @Schema(
                                    type = "string",
                                    minLength = 1,
                                    maxLength = OrderIdempotencyKeyHeader.MAX_LENGTH,
                                    pattern = OrderIdempotencyKeyHeader.OPENAPI_PATTERN))
            })
    @ApiResponse(
            responseCode = "201",
            description = "Order created, or durably replayed from a prior completed execution with the same "
                    + "Idempotency-Key and canonical content.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = OrderResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "The Idempotency-Key header is missing or syntactically invalid.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @ApiResponse(
            responseCode = "409",
            description = "The idempotency identity is still acquired by another request, or Catalog eligibility or Inventory commitment rejects this Order.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @ApiResponse(
            responseCode = "413",
            description = "The request exceeds the technical maximum number of items accepted by this endpoint.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @ApiResponse(
            responseCode = "422",
            description = "The Idempotency-Key was already used for a request with different canonical content.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @PostMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderResponse> create(
            @Parameter(hidden = true) TrustedActorContext actorContext,
            @Parameter(hidden = true) @RequestHeader HttpHeaders headers,
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
    @Operation(
            operationId = "ordersView",
            summary = "View a Customer's own order",
            description = "Returns one Order owned by the authenticated Customer actor, who must hold "
                    + "CUSTOMER_ORDERS_VIEW and own the referenced Order. An absent Order and an Order the "
                    + "actor does not own are reported identically as not found to avoid revealing existence "
                    + "or ownership to an unauthorized caller.",
            parameters = {
                    @Parameter(
                            name = "X-Tenant-Id",
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "Active Tenant selector, validated against the authenticated internal "
                                    + "identity, active membership and Tenant state.",
                            schema = @Schema(type = "string", format = "uuid"))
            })
    @ApiResponse(
            responseCode = "200",
            description = "Authorized persisted Order representation.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = OrderViewResponse.class)))
    @ApiResponse(responseCode = "404", description = "The Order is absent or is unavailable to the current Customer", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @GetMapping(
            value = "/{orderId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrderViewResponse> view(
            @Parameter(hidden = true) TrustedActorContext actorContext,
            @Parameter(description = "Requested Order identifier.") @PathVariable UUID orderId) {

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
