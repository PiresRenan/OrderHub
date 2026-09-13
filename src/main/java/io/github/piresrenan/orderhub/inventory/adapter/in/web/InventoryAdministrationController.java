package io.github.piresrenan.orderhub.inventory.adapter.in.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.math.BigDecimal;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import io.github.piresrenan.orderhub.security.application.model.TrustedActorContext;
import io.github.piresrenan.orderhub.inventory.application.port.in.*;
import io.github.piresrenan.orderhub.inventory.application.service.InventoryPolicyAdministrationService;
import io.github.piresrenan.orderhub.inventory.application.service.InventoryAdministrationReadService;
import io.github.piresrenan.orderhub.inventory.domain.model.*;

/** Transport mapping never accepts actor or Tenant authority from the command body. */
@Tag(name = "Inventory")
@RestController
@RequestMapping(value="/inventory",produces=MediaType.APPLICATION_JSON_VALUE)
public final class InventoryAdministrationController {
    private final RecordInventoryMovementUseCase movements;
    private final InventoryPolicyAdministrationService policies;
    private final InventoryAdministrationReadService reads;
    /** Composes movement, policy and bounded read contracts without owning stock state. */
    public InventoryAdministrationController(RecordInventoryMovementUseCase movements,InventoryPolicyAdministrationService policies,InventoryAdministrationReadService reads) {
        this.movements=movements; this.policies=policies; this.reads=reads;
    }
    /** Read Inventory policy; policy and state changes remain in the owner application boundary. */
    @Operation(operationId = "inventoryGetPolicy", summary = "Read Inventory policy",
            description = "Requires current Tenant Staff authority and INVENTORY_VIEW; bearer roles and upper administrative grants confer no Tenant permission. Returns the current Tenant policy.",
            parameters = @Parameter(name = "X-Tenant-Id", in = ParameterIn.HEADER, required = true,
                    description = "Requested Tenant selector; active membership and active Tenant are verified against the bearer identity.",
                    schema = @Schema(type = "string", format = "uuid")))
    @ApiResponse(responseCode = "200", description = "Read Inventory policy succeeded", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PolicyView.class)))
    @ApiResponse(responseCode = "404", description = "No current Inventory policy is available for this Tenant", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @GetMapping("/policy")
    PolicyView policy(@Parameter(hidden = true) TrustedActorContext actor) { return new PolicyView(reads.policy(actor.userId(),actor.tenantId())); }
    /** Read an Inventory position; policy and state changes remain in the owner application boundary. */
    @Operation(operationId = "inventoryGetPosition", summary = "Read an Inventory position",
            description = "Requires current Tenant Staff authority and INVENTORY_VIEW; bearer roles and upper administrative grants confer no Tenant permission. Authorization precedes sensitive lookup; absent or foreign resources are equivalent 404 responses. ",
            parameters = @Parameter(name = "X-Tenant-Id", in = ParameterIn.HEADER, required = true,
                    description = "Requested Tenant selector; active membership and active Tenant are verified against the bearer identity.",
                    schema = @Schema(type = "string", format = "uuid")))
    @ApiResponse(responseCode = "200", description = "Read an Inventory position succeeded", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PositionView.class)))
    @ApiResponse(responseCode = "404", description = "Authorized lookup found no available Variant or position in this Tenant", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @GetMapping("/positions/{variantId}")
    PositionView position(@Parameter(hidden = true) TrustedActorContext actor,@Parameter(description = "Tenant-owned Variant UUID selector") @PathVariable UUID variantId) {
        return PositionView.of(reads.position(actor.userId(),actor.tenantId(),variantId));
    }
    /** List Inventory positions; policy and state changes remain in the owner application boundary. */
    @Operation(operationId = "inventoryListPositions", summary = "List Inventory positions",
            description = "Requires current Tenant Staff authority and INVENTORY_VIEW; bearer roles and upper administrative grants confer no Tenant permission. Exclusive UUID cursor in PostgreSQL order, limit 1-100 (default 50). Returns a current-state array, not a frozen snapshot or chronological export. ",
            parameters = @Parameter(name = "X-Tenant-Id", in = ParameterIn.HEADER, required = true,
                    description = "Requested Tenant selector; active membership and active Tenant are verified against the bearer identity.",
                    schema = @Schema(type = "string", format = "uuid")))
    @ApiResponse(responseCode = "200", description = "List Inventory positions succeeded", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = PositionView.class))))
    @GetMapping("/positions")
    java.util.List<PositionView> positions(@Parameter(hidden = true) TrustedActorContext actor,@Parameter(description = "Exclusive PostgreSQL UUID cursor; omitted for the first page", schema = @Schema(type = "string", format = "uuid")) @RequestParam(required=false) UUID afterVariantId,@Parameter(description = "Maximum rows returned; current-state page without a total count", schema = @Schema(type = "integer", types = {"integer"}, format = "int32", minimum = "1", maximum = "100", defaultValue = "50")) @RequestParam(defaultValue="50") int limit) {
        return reads.positions(actor.userId(),actor.tenantId(),afterVariantId,limit).stream().map(PositionView::of).toList();
    }
    /** List Variant Inventory movements; policy and state changes remain in the owner application boundary. */
    @Operation(operationId = "inventoryListMovements", summary = "List Variant Inventory movements",
            description = "Requires current Tenant Staff authority and INVENTORY_VIEW; bearer roles and upper administrative grants confer no Tenant permission. Exclusive UUID cursor in PostgreSQL order, limit 1-100 (default 50). Returns a current-state array, not a frozen snapshot or chronological export. Includes original operational attribution and occurrence time. Movement UUID order is not chronological. Authorization precedes sensitive lookup; absent or foreign resources are equivalent 404 responses. ",
            parameters = @Parameter(name = "X-Tenant-Id", in = ParameterIn.HEADER, required = true,
                    description = "Requested Tenant selector; active membership and active Tenant are verified against the bearer identity.",
                    schema = @Schema(type = "string", format = "uuid")))
    @ApiResponse(responseCode = "200", description = "List Variant Inventory movements succeeded", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = InventoryMovement.class))))
    @ApiResponse(responseCode = "404", description = "Authorized lookup found no available Variant or position in this Tenant", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @GetMapping("/positions/{variantId}/movements")
    java.util.List<InventoryMovement> movements(@Parameter(hidden = true) TrustedActorContext actor,@Parameter(description = "Tenant-owned Variant UUID selector") @PathVariable UUID variantId,
            @Parameter(description = "Exclusive PostgreSQL UUID cursor; omitted for the first page", schema = @Schema(type = "string", format = "uuid")) @RequestParam(required=false) UUID afterOperationId,@Parameter(description = "Maximum rows returned; current-state page without a total count", schema = @Schema(type = "integer", types = {"integer"}, format = "int32", minimum = "1", maximum = "100", defaultValue = "50")) @RequestParam(defaultValue="50") int limit) {
        return reads.movements(actor.userId(),actor.tenantId(),variantId,afterOperationId,limit);
    }
    /** Receive physical stock; policy and state changes remain in the owner application boundary. */
    @Operation(operationId = "inventoryRecordReceipt", summary = "Receive physical stock",
            description = "Requires current Tenant Staff authority and INVENTORY_RECEIVE; bearer roles and upper administrative grants confer no Tenant permission. operationId is a durable Tenant-scoped retry identity. Matching actor, Variant, type, delta and reason replay the original movement with 201 and no new stock effect after current authorization. Different intent or actor conflicts. Correlation and occurrence time are server-owned. Quantity must be a strictly positive signed-64-bit integer. First receipt may create the position; it does not allocate existing backorders.",
            parameters = @Parameter(name = "X-Tenant-Id", in = ParameterIn.HEADER, required = true,
                    description = "Requested Tenant selector; active membership and active Tenant are verified against the bearer identity.",
                    schema = @Schema(type = "string", format = "uuid")))
    @ApiResponse(responseCode = "201", description = "Receive physical stock succeeded", content = @Content(mediaType = "application/json", schema = @Schema(implementation = InventoryMovement.class)))
    @ApiResponse(responseCode = "409", description = "Current stock, operation identity or expected state conflicts with the requested change", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Authorized lookup found no available Variant or position in this Tenant", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @PostMapping(value="/receipts",consumes=MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    InventoryMovement receipt(@Parameter(hidden = true) TrustedActorContext actor,@Valid @RequestBody ReceiptRequest request) {
        return movements.record(new InventoryMovementCommand(actor.userId(),actor.tenantId(),request.operationId(),request.variantId(),
                InventoryMovementType.RECEIPT,integer(request.quantity()),request.reason(),UUID.randomUUID()));
    }
    /** Adjust physical stock; policy and state changes remain in the owner application boundary. */
    @Operation(operationId = "inventoryRecordAdjustment", summary = "Adjust physical stock",
            description = "Requires current Tenant Staff authority and INVENTORY_ADJUST; bearer roles and upper administrative grants confer no Tenant permission. operationId is a durable Tenant-scoped retry identity. Matching actor, Variant, type, delta and reason replay the original movement with 201 and no new stock effect after current authorization. Different intent or actor conflicts. Correlation and occurrence time are server-owned. Delta must be nonzero and exclude the minimum signed value; physical stock cannot fall below commitments. Requires an existing mutable position.",
            parameters = @Parameter(name = "X-Tenant-Id", in = ParameterIn.HEADER, required = true,
                    description = "Requested Tenant selector; active membership and active Tenant are verified against the bearer identity.",
                    schema = @Schema(type = "string", format = "uuid")))
    @ApiResponse(responseCode = "201", description = "Adjust physical stock succeeded", content = @Content(mediaType = "application/json", schema = @Schema(implementation = InventoryMovement.class)))
    @ApiResponse(responseCode = "409", description = "Current stock, operation identity or expected state conflicts with the requested change", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Authorized lookup found no available Variant or expected policy", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @PostMapping(value="/adjustments",consumes=MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    InventoryMovement adjustment(@Parameter(hidden = true) TrustedActorContext actor,@Valid @RequestBody AdjustmentRequest request) {
        return movements.record(new InventoryMovementCommand(actor.userId(),actor.tenantId(),request.operationId(),request.variantId(),
                InventoryMovementType.ADJUSTMENT,integer(request.delta()),request.reason(),UUID.randomUUID()));
    }
    /** Set desired Inventory policy; policy and state changes remain in the owner application boundary. */
    @Operation(operationId = "inventorySetPolicy", summary = "Set desired Inventory policy",
            description = "Requires current Tenant Staff authority and INVENTORY_POLICY_MANAGE; bearer roles and upper administrative grants confer no Tenant permission. Null expectedPolicy initializes an absent policy. Already-desired state returns 200 despite an old expected value; changing desired state needs the actual expectedPolicy. Existing commitments remain unchanged; new Orders observe committed policy.",
            parameters = @Parameter(name = "X-Tenant-Id", in = ParameterIn.HEADER, required = true,
                    description = "Requested Tenant selector; active membership and active Tenant are verified against the bearer identity.",
                    schema = @Schema(type = "string", format = "uuid")))
    @ApiResponse(responseCode = "200", description = "Set desired Inventory policy succeeded", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PolicyView.class)))
    @ApiResponse(responseCode = "409", description = "Expected policy differs from current state", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Authorized lookup found no available Variant or expected policy", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @PutMapping(value="/policy",consumes=MediaType.APPLICATION_JSON_VALUE)
    PolicyView policy(@Parameter(hidden = true) TrustedActorContext actor,@Valid @RequestBody PolicyRequest request) {
        return new PolicyView(policies.policy(actor.userId(),actor.tenantId(),request.expectedPolicy(),request.policy(),request.reason(),UUID.randomUUID()));
    }
    /** Set desired safety stock; policy and state changes remain in the owner application boundary. */
    @Operation(operationId = "inventorySetSafetyStock", summary = "Set desired safety stock",
            description = "Requires current Tenant Staff authority and INVENTORY_POLICY_MANAGE; bearer roles and upper administrative grants confer no Tenant permission. Nonnegative signed-64-bit values. Already-desired state returns 200 even with an old expected value; a different desired state needs the actual expectedSafetyStock. May exceed physical availability; existing commitments remain unchanged.",
            parameters = @Parameter(name = "X-Tenant-Id", in = ParameterIn.HEADER, required = true,
                    description = "Requested Tenant selector; active membership and active Tenant are verified against the bearer identity.",
                    schema = @Schema(type = "string", format = "uuid")))
    @ApiResponse(responseCode = "200", description = "Set desired safety stock succeeded", content = @Content(mediaType = "application/json", schema = @Schema(implementation = PositionView.class)))
    @ApiResponse(responseCode = "409", description = "Current stock, operation identity or expected state conflicts with the requested change", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Authorized lookup found no available Variant or position in this Tenant", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @PutMapping(value="/positions/{variantId}/safety-stock",consumes=MediaType.APPLICATION_JSON_VALUE)
    PositionView safety(@Parameter(hidden = true) TrustedActorContext actor,@Parameter(description = "Tenant-owned Variant UUID selector") @PathVariable UUID variantId,@Valid @RequestBody SafetyRequest request) {
        return PositionView.of(policies.safetyStock(actor.userId(),actor.tenantId(),variantId,integer(request.expectedSafetyStock()),
                integer(request.safetyStock()),request.reason(),UUID.randomUUID()));
    }
    /** Rejects fractional or overflowing JSON quantities before invoking owner commands. */
    private static long integer(BigDecimal number) {
        if(number==null) throw new IllegalArgumentException("Required integer");
        try { return number.longValueExact(); }
        catch(ArithmeticException exception) { throw new IllegalArgumentException("Invalid integer"); }
    }
    @Schema(name = "InventoryReceiptRequest")
    record ReceiptRequest(
            @Schema(description = "Durable retry UUID, unique within the Tenant; preserve for matching retries", requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "uuid") @NotNull UUID operationId,
            @Schema(description = "Tenant-owned Variant UUID selector", requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "uuid") @NotNull UUID variantId,
            @Schema(description = "Exact strictly positive received quantity", requiredMode = Schema.RequiredMode.REQUIRED, type = "integer", types = {"integer"}, format = "int64", minimum = "1", maximum = "9223372036854775807") @NotNull BigDecimal quantity,
            @Schema(description = "Bounded operational reason code; never free-form personal information", requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 64, pattern = "^(?:[A-Z][A-Z0-9_]{0,63})$") @NotNull @Pattern(regexp="[A-Z][A-Z0-9_]{0,63}") String reason) {}
    @Schema(name = "InventoryAdjustmentRequest")
    record AdjustmentRequest(
            @Schema(description = "Durable retry UUID, unique within the Tenant; preserve for matching retries", requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "uuid") @NotNull UUID operationId,
            @Schema(description = "Tenant-owned Variant UUID selector", requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "uuid") @NotNull UUID variantId,
            @Schema(description = "Exact signed correction; nonzero and excludes the minimum signed value", requiredMode = Schema.RequiredMode.REQUIRED, type = "integer", types = {"integer"}, format = "int64", minimum = "-9223372036854775807", maximum = "9223372036854775807") @NotNull BigDecimal delta,
            @Schema(description = "Bounded operational reason code; never free-form personal information", requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 64, pattern = "^(?:[A-Z][A-Z0-9_]{0,63})$") @NotNull @Pattern(regexp="[A-Z][A-Z0-9_]{0,63}") String reason) {}
    @Schema(name = "InventoryPolicyRequest")
    record PolicyRequest(
            @Schema(description = "Previously observed policy; null only to initialize absence", requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true) InventoryPolicy expectedPolicy,
            @Schema(description = "Current or desired allocation policy: DENY or ALLOW_BACKORDER", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull InventoryPolicy policy,
            @Schema(description = "Bounded operational reason code; never free-form personal information", requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 64, pattern = "^(?:[A-Z][A-Z0-9_]{0,63})$") @NotNull @Pattern(regexp="[A-Z][A-Z0-9_]{0,63}") String reason) {}
    @Schema(name = "InventorySafetyRequest")
    record SafetyRequest(
            @Schema(description = "Previously observed nonnegative safety stock", requiredMode = Schema.RequiredMode.REQUIRED, type = "integer", types = {"integer"}, format = "int64", minimum = "0", maximum = "9223372036854775807") @NotNull BigDecimal expectedSafetyStock,
            @Schema(description = "Desired nonnegative safety stock; may exceed physical availability", requiredMode = Schema.RequiredMode.REQUIRED, type = "integer", types = {"integer"}, format = "int64", minimum = "0", maximum = "9223372036854775807") @NotNull BigDecimal safetyStock,
            @Schema(description = "Bounded operational reason code; never free-form personal information", requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 64, pattern = "^(?:[A-Z][A-Z0-9_]{0,63})$") @NotNull @Pattern(regexp="[A-Z][A-Z0-9_]{0,63}") String reason) {}
    @Schema(name = "InventoryPolicyView")
    record PolicyView(
            @Schema(description = "Current or desired allocation policy: DENY or ALLOW_BACKORDER", requiredMode = Schema.RequiredMode.REQUIRED) InventoryPolicy policy) {}
    @Schema(name = "InventoryPositionView")
    record PositionView(
            @Schema(description = "Tenant-owned Variant UUID selector", requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "uuid") UUID variantId,
            @Schema(description = "Physical stock on hand", requiredMode = Schema.RequiredMode.REQUIRED, type = "integer", types = {"integer"}, format = "int64", minimum = "0", maximum = "9223372036854775807") long onHand,
            @Schema(description = "Existing committed physical quantity", requiredMode = Schema.RequiredMode.REQUIRED, type = "integer", types = {"integer"}, format = "int64", minimum = "0", maximum = "9223372036854775807") long committed,
            @Schema(description = "Existing accepted backordered demand", requiredMode = Schema.RequiredMode.REQUIRED, type = "integer", types = {"integer"}, format = "int64", minimum = "0", maximum = "9223372036854775807") long backordered,
            @Schema(description = "Desired nonnegative safety stock; may exceed physical availability", requiredMode = Schema.RequiredMode.REQUIRED, type = "integer", types = {"integer"}, format = "int64", minimum = "0", maximum = "9223372036854775807") long safetyStock) {
        /** Projects current stock quantities without exposing persistence coordination state. */
        static PositionView of(InventoryPosition position) {
            return new PositionView(position.variantId(),position.onHand(),position.committed(),position.backordered(),position.safetyStock());
        }
    }
}
