package io.github.piresrenan.orderhub.customers.application.port.in;

import java.util.UUID;

import org.springframework.modulith.NamedInterface;

/**
 * Resolves whether one internal User is explicitly bound to one Customer
 * inside one Tenant.
 */
@NamedInterface("account-binding")
public interface ResolveCustomerAccountBindingUseCase {

    CustomerAccountBindingResolution resolve(
            UUID tenantId,
            UUID customerId,
            UUID userId);
}
