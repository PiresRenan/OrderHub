package io.github.piresrenan.orderhub.orders.application.port.out;

import java.util.UUID;

@FunctionalInterface
public interface OrderIdGenerator {
    /**
     * Generates the identity assigned to a newly created order.
     *
     * <p>
     * The abstraction keeps identity generation deterministic in tests and
     * replaceable if a future persistence or distributed strategy requires a
     * different identifier mechanism.
     * </p>
     *
     * @return a new order identifier
     */
    UUID generate();
}
