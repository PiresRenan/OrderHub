package io.github.piresrenan.orderhub.catalog.application.port.in.orderability;

/**
 * Validates and stabilizes Catalog sellability for Order placement.
 */
@FunctionalInterface
public interface ValidateOrderableVariantsUseCase {

    void validate(
            ValidateOrderableVariantsCommand command);
}
