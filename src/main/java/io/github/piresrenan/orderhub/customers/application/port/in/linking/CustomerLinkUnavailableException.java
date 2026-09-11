package io.github.piresrenan.orderhub.customers.application.port.in.linking;

/** Uniform denial for inaccessible selectors, invalid proof and incompatible operation reuse. */
public final class CustomerLinkUnavailableException extends RuntimeException {
    /** Creates the bounded failure classification without exposing private inputs or infrastructure details. */
    public CustomerLinkUnavailableException() { super("Customer account linking is unavailable"); }
}
