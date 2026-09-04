package io.github.piresrenan.orderhub.orders.application.port.in;

/**
 * Signals a denied Customer self-service Order action without exposing whether
 * an account binding or another authorization fact was missing.
 */
public final class CustomerOrderAccessDeniedException
        extends RuntimeException {

    public CustomerOrderAccessDeniedException() {

        super(
                "Customer order access denied.");
    }
}
