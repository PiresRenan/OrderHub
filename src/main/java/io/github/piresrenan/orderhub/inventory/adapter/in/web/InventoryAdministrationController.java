package io.github.piresrenan.orderhub.inventory.adapter.in.web;

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
@RestController
@RequestMapping(value="/inventory",produces=MediaType.APPLICATION_JSON_VALUE)
public final class InventoryAdministrationController {
    private final RecordInventoryMovementUseCase movements;
    private final InventoryPolicyAdministrationService policies;
    private final InventoryAdministrationReadService reads;
    public InventoryAdministrationController(RecordInventoryMovementUseCase movements,InventoryPolicyAdministrationService policies,InventoryAdministrationReadService reads) {
        this.movements=movements; this.policies=policies; this.reads=reads;
    }
    @GetMapping("/policy")
    PolicyView policy(TrustedActorContext actor) { return new PolicyView(reads.policy(actor.userId(),actor.tenantId())); }
    @GetMapping("/positions/{variantId}")
    PositionView position(TrustedActorContext actor,@PathVariable UUID variantId) {
        return PositionView.of(reads.position(actor.userId(),actor.tenantId(),variantId));
    }
    @GetMapping("/positions")
    java.util.List<PositionView> positions(TrustedActorContext actor,@RequestParam(required=false) UUID afterVariantId,@RequestParam(defaultValue="50") int limit) {
        return reads.positions(actor.userId(),actor.tenantId(),afterVariantId,limit).stream().map(PositionView::of).toList();
    }
    @GetMapping("/positions/{variantId}/movements")
    java.util.List<InventoryMovement> movements(TrustedActorContext actor,@PathVariable UUID variantId,
            @RequestParam(required=false) UUID afterOperationId,@RequestParam(defaultValue="50") int limit) {
        return reads.movements(actor.userId(),actor.tenantId(),variantId,afterOperationId,limit);
    }
    @PostMapping(value="/receipts",consumes=MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    InventoryMovement receipt(TrustedActorContext actor,@Valid @RequestBody ReceiptRequest request) {
        return movements.record(new InventoryMovementCommand(actor.userId(),actor.tenantId(),request.operationId(),request.variantId(),
                InventoryMovementType.RECEIPT,integer(request.quantity()),request.reason(),UUID.randomUUID()));
    }
    @PostMapping(value="/adjustments",consumes=MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    InventoryMovement adjustment(TrustedActorContext actor,@Valid @RequestBody AdjustmentRequest request) {
        return movements.record(new InventoryMovementCommand(actor.userId(),actor.tenantId(),request.operationId(),request.variantId(),
                InventoryMovementType.ADJUSTMENT,integer(request.delta()),request.reason(),UUID.randomUUID()));
    }
    @PutMapping(value="/policy",consumes=MediaType.APPLICATION_JSON_VALUE)
    PolicyView policy(TrustedActorContext actor,@Valid @RequestBody PolicyRequest request) {
        return new PolicyView(policies.policy(actor.userId(),actor.tenantId(),request.expectedPolicy(),request.policy(),request.reason(),UUID.randomUUID()));
    }
    @PutMapping(value="/positions/{variantId}/safety-stock",consumes=MediaType.APPLICATION_JSON_VALUE)
    PositionView safety(TrustedActorContext actor,@PathVariable UUID variantId,@Valid @RequestBody SafetyRequest request) {
        return PositionView.of(policies.safetyStock(actor.userId(),actor.tenantId(),variantId,integer(request.expectedSafetyStock()),
                integer(request.safetyStock()),request.reason(),UUID.randomUUID()));
    }
    private static long integer(BigDecimal number) {
        if(number==null) throw new IllegalArgumentException("Required integer");
        try { return number.longValueExact(); }
        catch(ArithmeticException exception) { throw new IllegalArgumentException("Invalid integer"); }
    }
    record ReceiptRequest(@NotNull UUID operationId,@NotNull UUID variantId,@NotNull BigDecimal quantity,
            @NotNull @Pattern(regexp="[A-Z][A-Z0-9_]{0,63}") String reason) {}
    record AdjustmentRequest(@NotNull UUID operationId,@NotNull UUID variantId,@NotNull BigDecimal delta,
            @NotNull @Pattern(regexp="[A-Z][A-Z0-9_]{0,63}") String reason) {}
    record PolicyRequest(InventoryPolicy expectedPolicy,@NotNull InventoryPolicy policy,
            @NotNull @Pattern(regexp="[A-Z][A-Z0-9_]{0,63}") String reason) {}
    record SafetyRequest(@NotNull BigDecimal expectedSafetyStock,@NotNull BigDecimal safetyStock,
            @NotNull @Pattern(regexp="[A-Z][A-Z0-9_]{0,63}") String reason) {}
    record PolicyView(InventoryPolicy policy) {}
    record PositionView(UUID variantId,long onHand,long committed,long backordered,long safetyStock) {
        static PositionView of(InventoryPosition position) {
            return new PositionView(position.variantId(),position.onHand(),position.committed(),position.backordered(),position.safetyStock());
        }
    }
}
