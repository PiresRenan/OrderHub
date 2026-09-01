package io.github.piresrenan.orderhub.inventory.application.port.out;

import java.time.Instant;

/**
 * Supplies the timestamp attached to durable inventory commitment facts.
 */
public interface InventoryTimeProvider {

    Instant now();
}