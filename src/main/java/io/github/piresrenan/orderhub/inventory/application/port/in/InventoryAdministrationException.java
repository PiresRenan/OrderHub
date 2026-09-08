package io.github.piresrenan.orderhub.inventory.application.port.in;

/** Bounded, non-enumerating administration outcomes; causes remain infrastructure-only. */
public final class InventoryAdministrationException extends RuntimeException {
    public enum Reason { ACCESS_DENIED, TARGET_UNAVAILABLE, CONFLICT, QUANTITY_CONFLICT, TECHNICAL }
    private final Reason reason;
    /** Represents an explicit bounded business outcome without persistence details. */
    public InventoryAdministrationException(Reason reason) { this(reason, null); }
    /** Retains diagnostic cause privately while exposing only a fixed public message. */
    public InventoryAdministrationException(Reason reason, Throwable cause) {
        super("Inventory administration could not complete", cause);
        this.reason = java.util.Objects.requireNonNull(reason);
    }
    /** Returns the bounded public outcome classification. */
    public Reason reason() { return reason; }
}
