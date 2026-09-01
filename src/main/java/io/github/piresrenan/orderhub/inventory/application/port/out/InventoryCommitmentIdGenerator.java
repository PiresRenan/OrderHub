package io.github.piresrenan.orderhub.inventory.application.port.out;

import java.util.UUID;

/**
 * Generates durable InventoryCommitment identities without coupling the
 * application service to a concrete UUID mechanism.
 */
public interface InventoryCommitmentIdGenerator {

    UUID generate();
}