package io.github.piresrenan.orderhub.customers.application.port.in;

import org.springframework.modulith.NamedInterface;

/**
 * Stable cross-module representation of a technical failure while resolving
 * one Customer account binding.
 *
 * <p>
 * Persistence-specific exceptions remain internal to Customers. Callers
 * receive this API-level failure without SQL, storage technology or business
 * identifiers being exposed.
 * </p>
 */
@NamedInterface("account-binding")
public final class CustomerAccountBindingTechnicalException
        extends RuntimeException {

    public CustomerAccountBindingTechnicalException(
            Throwable cause) {

        super(
                "Customer account binding could not be resolved.",
                cause);
    }
}
